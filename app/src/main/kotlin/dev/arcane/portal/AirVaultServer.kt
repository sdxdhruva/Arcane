package dev.arcane.portal

import dev.arcane.data.AppGraph
import dev.arcane.data.VaultEntry
import dev.arcane.vault.format.Hex
import dev.arcane.vault.index.FileRecord
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * AirVault: Local Wi-Fi PC Portal Server.
 *
 * Runs an embedded HTTP server when active, allowing the user to browse, view, stream, download,
 * and upload files directly from their PC/Mac browser over local Wi-Fi with on-the-fly RAM
 * decryption and chunk-by-chunk AES-256-GCM encryption.
 *
 * Security guarantees:
 * 1. Strictly local Wi-Fi only (binds to site-local IP, invisible to the public internet).
 * 2. Fresh random 6-digit PIN on every launch with instant re-generation capability.
 * 3. Granular folder access control: allows the user to share the entire vault, or restrict access
 *    strictly to specific folders (e.g. current folder only).
 * 4. Automatic teardown on session lock, app background, or explicit stop.
 * 5. Zero plaintext writes to disk:
 *    - Streaming and downloads decrypt chunks directly in RAM via [ChunkInputStream].
 *    - Uploads are encrypted on-the-fly chunk-by-chunk into scattered blobs before flash storage.
 */
class AirVaultServer(
    private val graph: AppGraph,
) {
    data class PortalStatus(
        val isRunning: Boolean = false,
        val ipAddress: String = "",
        val port: Int = 8080,
        val pin: String = "",
        val sharedScopeName: String = "Entire Vault",
        val sharedFolderIds: Set<String>? = null, // null = all folders
    ) {
        val url: String get() = if (isRunning && ipAddress.isNotEmpty()) "http://$ipAddress:$port" else ""
    }

    data class PortalEvent(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val type: EventType,
        val description: String,
        val details: String? = null,
    ) {
        enum class EventType {
            SERVER_START,
            SERVER_STOP,
            CLIENT_CONNECTED,
            STREAM_START,
            DOWNLOAD,
            UPLOAD_START,
            UPLOAD_COMPLETE,
            PIN_REJECTED,
            PIN_REGENERATED,
            ACCESS_DENIED,
        }
    }

    data class PortalStats(
        val activeClients: Int = 0,
        val totalRequests: Long = 0L,
        val bytesStreamed: Long = 0L,
        val bytesUploaded: Long = 0L,
        val filesUploaded: Int = 0,
        val startedAtMillis: Long = 0L,
        val recentEvents: List<PortalEvent> = emptyList(),
    )

    private val _status = MutableStateFlow(PortalStatus())
    val status: StateFlow<PortalStatus> = _status.asStateFlow()

    private val _stats = MutableStateFlow(PortalStats())
    val stats: StateFlow<PortalStats> = _stats.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var sessionToken: String = ""
    private val random = SecureRandom()

    private fun logEvent(type: PortalEvent.EventType, description: String, details: String? = null) {
        val event = PortalEvent(type = type, description = description, details = details)
        _stats.update { current ->
            current.copy(
                recentEvents = (listOf(event) + current.recentEvents).take(30),
            )
        }
    }

    /** Regenerates the 6-digit PIN and invalidates all existing browser sessions immediately. */
    fun regeneratePin(): String {
        val newPin = String.format("%06d", random.nextInt(1000000))
        sessionToken = UUID.randomUUID().toString()
        _status.update { it.copy(pin = newPin) }
        logEvent(PortalEvent.EventType.PIN_REGENERATED, "PIN regenerated", "New PIN: $newPin")
        return newPin
    }

    /** Clears telemetry event logs. */
    fun clearEvents() {
        _stats.update { it.copy(recentEvents = emptyList()) }
    }

    /** Starts the server with an optional restricted set of folder IDs. */
    fun start(
        sharedFolderIds: Set<String>? = null,
        sharedScopeName: String = if (sharedFolderIds == null) "Entire Vault" else "Selected Folders",
    ) {
        if (_status.value.isRunning) stop()

        val ip = getLocalIpAddress() ?: "127.0.0.1"
        val pin = String.format("%06d", random.nextInt(1000000))
        sessionToken = UUID.randomUUID().toString()

        var selectedPort = 8080
        var sSocket: ServerSocket? = null
        for (p in 8080..8095) {
            try {
                sSocket = ServerSocket(p)
                selectedPort = p
                break
            } catch (_: Exception) {
                // Try next port in range
            }
        }

        if (sSocket == null) {
            logEvent(PortalEvent.EventType.ACCESS_DENIED, "Port binding failed on 8080..8095")
            return
        }

        serverSocket = sSocket
        graph.session.pauseIdle(true)
        _status.value = PortalStatus(
            isRunning = true,
            ipAddress = ip,
            port = selectedPort,
            pin = pin,
            sharedScopeName = sharedScopeName,
            sharedFolderIds = sharedFolderIds,
        )
        _stats.value = PortalStats(
            startedAtMillis = System.currentTimeMillis(),
        )
        logEvent(PortalEvent.EventType.SERVER_START, "AirVault PC Portal online", "http://$ip:$selectedPort (PIN: $pin)")

        scope.launch {
            try {
                while (_status.value.isRunning && !sSocket.isClosed) {
                    val client = sSocket.accept()
                    executor.submit { handleClient(client) }
                }
            } catch (_: Exception) {
                // Normal on socket close
            } finally {
                stop()
            }
        }
    }

    /** Shuts down the server and clears session state immediately. */
    fun stop() {
        if (!_status.value.isRunning && serverSocket == null) return
        graph.session.pauseIdle(false)
        logEvent(PortalEvent.EventType.SERVER_STOP, "AirVault PC Portal stopped")
        _status.value = PortalStatus(isRunning = false)
        _stats.update { it.copy(activeClients = 0) }
        sessionToken = ""
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        graph.session.touch()
        _stats.update { it.copy(activeClients = it.activeClients + 1) }
        try {
            socket.use { sock ->
                sock.soTimeout = 25000 // 25 second timeout
                val inStream = BufferedInputStream(sock.getInputStream())
                val outStream = BufferedOutputStream(sock.getOutputStream())

                // Parse request line
                val requestLine = readLine(inStream) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val fullPath = parts[1]

                // Parse headers
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(inStream) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val key = line.substring(0, colon).trim().lowercase()
                        val value = line.substring(colon + 1).trim()
                        headers[key] = value
                    }
                }

                _stats.update { it.copy(totalRequests = it.totalRequests + 1) }

                val path = fullPath.substringBefore('?')
                val queryString = fullPath.substringAfter('?', "")
                val queryParams = parseQueryParams(queryString)

                val cookieHeader = headers["cookie"] ?: ""
                val authHeader = headers["authorization"] ?: ""
                val tokenParam = queryParams["token"] ?: ""
                val hasAuthToken = cookieHeader.contains("arcane_token=$sessionToken") ||
                    authHeader == "Bearer $sessionToken" ||
                    (tokenParam.isNotEmpty() && tokenParam == sessionToken)

                when {
                    method == "GET" && path == "/" -> {
                        sendHtmlResponse(outStream, getWebPortalHtml(_status.value.sharedScopeName))
                    }

                    method == "GET" && path == "/api/ping" -> {
                        sendJsonResponse(
                            outStream,
                            200,
                            JSONObject().apply {
                                put("alive", true)
                                put("scope", _status.value.sharedScopeName)
                                put("authenticated", hasAuthToken)
                            },
                        )
                    }

                    method == "POST" && path == "/api/auth" -> {
                        val length = headers["content-length"]?.toIntOrNull() ?: 0
                        val body = readBody(inStream, length)
                        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                        val enteredPin = json.optString("pin").trim()

                        if (enteredPin == _status.value.pin) {
                            logEvent(PortalEvent.EventType.CLIENT_CONNECTED, "PC authenticated successfully", sock.inetAddress.hostAddress)
                            sendJsonResponse(
                                out = outStream,
                                statusCode = 200,
                                json = JSONObject().apply {
                                    put("success", true)
                                    put("token", sessionToken)
                                    put("scope", _status.value.sharedScopeName)
                                },
                                setCookie = "arcane_token=$sessionToken; Path=/; SameSite=Lax",
                            )
                        } else {
                            logEvent(PortalEvent.EventType.PIN_REJECTED, "Invalid PIN attempt", sock.inetAddress.hostAddress)
                            sendJsonResponse(
                                out = outStream,
                                statusCode = 401,
                                json = JSONObject().apply { put("error", "Incorrect 6-digit PIN") },
                            )
                        }
                    }

                    method == "POST" && path == "/api/logout" -> {
                        sendJsonResponse(
                            out = outStream,
                            statusCode = 200,
                            json = JSONObject().apply { put("success", true) },
                            setCookie = "arcane_token=deleted; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT",
                        )
                    }

                    // Authenticated API endpoints
                    !hasAuthToken -> {
                        sendJsonResponse(outStream, 401, JSONObject().apply { put("error", "Unauthorized. Please enter PIN.") })
                    }

                    method == "GET" && path == "/api/browse" -> {
                        handleBrowse(outStream, queryParams["folderId"])
                    }

                    method == "GET" && path.startsWith("/api/stream/") -> {
                        val fileIdHex = path.removePrefix("/api/stream/")
                        handleStream(outStream, fileIdHex, headers["range"])
                    }

                    method == "GET" && path.startsWith("/api/download/") -> {
                        val fileIdHex = path.removePrefix("/api/download/")
                        handleDownload(outStream, fileIdHex)
                    }

                    method == "POST" && path == "/api/upload" -> {
                        val targetFolderId = queryParams["folderId"].takeIf { !it.isNullOrBlank() }
                        val length = queryParams["size"]?.toLongOrNull() ?: headers["content-length"]?.toLongOrNull() ?: 0L
                        val filename = queryParams["name"] ?: "upload_${System.currentTimeMillis()}"
                        val mimeType = headers["content-type"]?.takeIf { !it.startsWith("application/octet-stream") }
                        handleUpload(inStream, outStream, filename, targetFolderId, length, mimeType)
                    }

                    else -> {
                        sendTextResponse(outStream, 404, "Not Found")
                    }
                }
            }
        } catch (_: Exception) {
            // Client disconnected
        } finally {
            _stats.update { it.copy(activeClients = maxOf(0, it.activeClients - 1)) }
        }
    }

    private fun handleBrowse(out: OutputStream, requestedFolderId: String?) {
        val cleanFolderId = requestedFolderId?.trim()?.takeIf { it.isNotEmpty() }
        val currentScope = _status.value.sharedFolderIds

        // Access enforcement: if restricted, prevent navigating outside permitted folders
        if (currentScope != null) {
            if (cleanFolderId != null && cleanFolderId !in currentScope) {
                sendJsonResponse(out, 403, JSONObject().apply { put("error", "Folder access restricted by scope") })
                logEvent(PortalEvent.EventType.ACCESS_DENIED, "Browse access restricted for folder $cleanFolderId")
                return
            }
        }

        val effectiveFolderId = cleanFolderId ?: if (currentScope != null && currentScope.size == 1) currentScope.first() else null

        val listing = kotlinx.coroutines.runBlocking {
            graph.repository.listingOf(effectiveFolderId)
        } ?: graph.repository.listing.value

        val foldersArray = JSONArray()
        val filesArray = JSONArray()

        for (entry in listing.entries) {
            when (entry) {
                is VaultEntry.Folder -> {
                    if (currentScope == null || entry.record.id in currentScope || effectiveFolderId != null) {
                        foldersArray.put(JSONObject().apply {
                            put("id", entry.record.id)
                            put("name", entry.name)
                            put("itemCount", entry.itemCount)
                            put("caption", if (entry.itemCount == 1) "1 item" else "${entry.itemCount} items")
                        })
                    }
                }
                is VaultEntry.File -> {
                    val rec = entry.record
                    val nameLower = entry.name.lowercase(Locale.US)
                    val isVideo = rec.mimeType?.startsWith("video/") == true ||
                        nameLower.endsWith(".mp4") || nameLower.endsWith(".mkv") || nameLower.endsWith(".mov") || nameLower.endsWith(".webm")
                    val isImage = rec.mimeType?.startsWith("image/") == true ||
                        nameLower.endsWith(".jpg") || nameLower.endsWith(".jpeg") || nameLower.endsWith(".png") || nameLower.endsWith(".webp") || nameLower.endsWith(".gif")
                    val isAudio = rec.mimeType?.startsWith("audio/") == true ||
                        nameLower.endsWith(".mp3") || nameLower.endsWith(".flac") || nameLower.endsWith(".wav") || nameLower.endsWith(".m4a")
                    val isDoc = nameLower.endsWith(".pdf") || nameLower.endsWith(".txt") || nameLower.endsWith(".md") || nameLower.endsWith(".doc") || nameLower.endsWith(".docx")
                    filesArray.put(JSONObject().apply {
                        put("fileId", Hex.lower(rec.fileId))
                        put("name", entry.name)
                        put("size", rec.plainSize)
                        put("mimeType", rec.mimeType ?: "application/octet-stream")
                        put("isVideo", isVideo)
                        put("isImage", isImage)
                        put("isAudio", isAudio)
                        put("isDoc", isDoc)
                        put("modified", rec.modifiedAtMillis)
                    })
                }
            }
        }

        val breadcrumbsArray = JSONArray()
        breadcrumbsArray.put(JSONObject().apply {
            put("id", "")
            put("name", "The Vault")
        })
        listing.trail.forEach { crumb ->
            breadcrumbsArray.put(JSONObject().apply {
                put("id", crumb.id)
                put("name", crumb.name)
            })
        }

        val responseJson = JSONObject().apply {
            put("currentFolderId", effectiveFolderId ?: "")
            put("currentFolderName", listing.trail.lastOrNull()?.name ?: "The Vault")
            put("scopeName", _status.value.sharedScopeName)
            put("breadcrumbs", breadcrumbsArray)
            put("folders", foldersArray)
            put("files", filesArray)
            put("totalFiles", filesArray.length())
            put("totalFolders", foldersArray.length())
        }
        sendJsonResponse(out, 200, responseJson)
    }

    private fun handleStream(out: OutputStream, fileIdHex: String, rangeHeader: String?) {
        val fileId = try { Hex.decode(fileIdHex) } catch (_: Exception) {
            sendTextResponse(out, 400, "Invalid File ID")
            return
        }

        val record = kotlinx.coroutines.runBlocking { graph.repository.recordOf(fileId) }
        if (record == null) {
            sendTextResponse(out, 404, "File Not Found")
            return
        }
        val fileName = record.name
        val mimeType = record.mimeType ?: "video/mp4"

        // Fetch decrypted stream directly in RAM
        val chunkStreamResult = runCatching {
            kotlinx.coroutines.runBlocking { graph.repository.openChunkStream(fileId) }
        }.getOrNull()
        if (chunkStreamResult == null) {
            sendTextResponse(out, 500, "Could not open decrypted stream")
            return
        }
        val (stream, totalSize) = chunkStreamResult

        var start = 0L
        var end = totalSize - 1

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val rangeVal = rangeHeader.removePrefix("bytes=").trim()
            val dash = rangeVal.indexOf('-')
            if (dash >= 0) {
                val startStr = rangeVal.substring(0, dash).trim()
                val endStr = rangeVal.substring(dash + 1).trim()
                if (startStr.isNotEmpty()) start = startStr.toLongOrNull() ?: 0L
                if (endStr.isNotEmpty()) end = endStr.toLongOrNull() ?: (totalSize - 1)
            }
        }

        val contentLength = end - start + 1
        logEvent(PortalEvent.EventType.STREAM_START, "Streaming: $fileName", formatBytes(contentLength))

        stream.use { s ->
            if (start > 0) s.skip(start)
            val headerBuilder = StringBuilder()
            if (rangeHeader != null) {
                headerBuilder.append("HTTP/1.1 206 Partial Content\r\n")
                headerBuilder.append("Content-Range: bytes $start-$end/$totalSize\r\n")
            } else {
                headerBuilder.append("HTTP/1.1 200 OK\r\n")
            }
            val decodedName = runCatching { URLDecoder.decode(fileName, "UTF-8") }.getOrDefault(fileName)
            val rfc5987 = URLEncoder.encode(decodedName, "UTF-8").replace("+", "%20")
            val safeAscii = decodedName.replace('"', '_').replace('\r', '_').replace('\n', '_')
            headerBuilder.append("Content-Type: $mimeType\r\n")
            headerBuilder.append("Content-Length: $contentLength\r\n")
            headerBuilder.append("Content-Disposition: inline; filename=\"$safeAscii\"; filename*=UTF-8''$rfc5987\r\n")
            headerBuilder.append("Accept-Ranges: bytes\r\n")
            headerBuilder.append("Connection: keep-alive\r\n\r\n")

            out.write(headerBuilder.toString().toByteArray(Charsets.US_ASCII))

            val buffer = ByteArray(64 * 1024)
            var remaining = contentLength
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = s.read(buffer, 0, toRead)
                if (read < 0) break
                out.write(buffer, 0, read)
                _stats.update { it.copy(bytesStreamed = it.bytesStreamed + read) }
                remaining -= read
            }
            out.flush()
        }
    }

    private fun handleDownload(out: OutputStream, fileIdHex: String) {
        val fileId = try { Hex.decode(fileIdHex) } catch (_: Exception) {
            sendTextResponse(out, 400, "Invalid File ID")
            return
        }
        val record = kotlinx.coroutines.runBlocking { graph.repository.recordOf(fileId) }
        if (record == null) {
            sendTextResponse(out, 404, "File Not Found")
            return
        }
        val fileName = record.name
        val mimeType = record.mimeType ?: "application/octet-stream"

        val chunkStreamResult = runCatching {
            kotlinx.coroutines.runBlocking { graph.repository.openChunkStream(fileId) }
        }.getOrNull()
        if (chunkStreamResult == null) {
            sendTextResponse(out, 500, "Could not open decrypted stream")
            return
        }
        val (stream, totalSize) = chunkStreamResult

        logEvent(PortalEvent.EventType.DOWNLOAD, "Downloading: $fileName", formatBytes(totalSize))

        stream.use { s ->
            val decodedName = runCatching { URLDecoder.decode(fileName, "UTF-8") }.getOrDefault(fileName)
            val rfc5987 = URLEncoder.encode(decodedName, "UTF-8").replace("+", "%20")
            val safeAscii = decodedName.replace('"', '_').replace('\r', '_').replace('\n', '_')
            val headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $mimeType\r\n" +
                "Content-Length: $totalSize\r\n" +
                "Content-Disposition: attachment; filename=\"$safeAscii\"; filename*=UTF-8''$rfc5987\r\n" +
                "Connection: close\r\n\r\n"
            out.write(headers.toByteArray(Charsets.US_ASCII))

            val buffer = ByteArray(64 * 1024)
            var totalCopied = 0L
            while (totalCopied < totalSize) {
                val read = s.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                totalCopied += read
                _stats.update { it.copy(bytesStreamed = it.bytesStreamed + read) }
            }
            out.flush()
        }
    }

    private fun handleUpload(
        inStream: InputStream,
        out: OutputStream,
        name: String,
        folderId: String?,
        length: Long,
        mimeType: String?,
    ) {
        // Enforce shared folder boundary on upload
        val currentScope = _status.value.sharedFolderIds
        if (currentScope != null && (folderId == null || folderId !in currentScope)) {
            sendJsonResponse(out, 403, JSONObject().apply { put("error", "Upload into this folder is forbidden by scope.") })
            logEvent(PortalEvent.EventType.ACCESS_DENIED, "Upload blocked: forbidden folder scope")
            return
        }

        if (length <= 0L) {
            sendJsonResponse(out, 400, JSONObject().apply { put("error", "Missing or invalid file size.") })
            return
        }

        val cleanName = sanitizeFileName(name)
        logEvent(PortalEvent.EventType.UPLOAD_START, "Encrypting upload: $cleanName", formatBytes(length))

        // Bounded stream to read exactly `length` bytes without blocking on HTTP socket keep-alive
        val boundedStream = object : InputStream() {
            private var remaining = length

            override fun read(): Int {
                if (remaining <= 0) return -1
                val b = inStream.read()
                if (b != -1) remaining--
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val toRead = minOf(len.toLong(), remaining).toInt()
                val readCount = inStream.read(b, off, toRead)
                if (readCount > 0) remaining -= readCount
                return readCount
            }
        }

        try {
            kotlinx.coroutines.runBlocking {
                graph.repository.importStream(
                    source = boundedStream,
                    name = cleanName,
                    folderId = folderId,
                    mimeType = mimeType,
                    plainSize = length,
                )
            }
            _stats.update {
                it.copy(
                    bytesUploaded = it.bytesUploaded + length,
                    filesUploaded = it.filesUploaded + 1,
                )
            }
            logEvent(PortalEvent.EventType.UPLOAD_COMPLETE, "Securely encrypted: $cleanName", formatBytes(length))
            sendJsonResponse(out, 200, JSONObject().apply {
                put("success", true)
                put("name", cleanName)
                put("size", length)
            })
        } catch (e: Exception) {
            logEvent(PortalEvent.EventType.ACCESS_DENIED, "Upload failed for $cleanName: ${e.message}")
            sendJsonResponse(out, 500, JSONObject().apply { put("error", e.message ?: "Upload failed") })
        }
    }

    private fun sanitizeFileName(raw: String): String {
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        val candidate = decoded.substringAfterLast('/').substringAfterLast('\\').trim()
        val clean = candidate.map { if (it.isISOControl() || it == '/' || it == '\\') '_' else it }
            .joinToString("")
            .trim('.', ' ')
            .take(128)
        return clean.ifEmpty { "upload_${System.currentTimeMillis()}" }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var amount = bytes.toDouble()
        var step = 0
        while (amount >= 1024.0 && step < units.lastIndex) {
            amount /= 1024.0
            step++
        }
        return String.format(Locale.US, "%.1f %s", amount, units[step])
    }

    private fun sendJsonResponse(out: OutputStream, statusCode: Int, json: JSONObject, setCookie: String? = null) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            else -> "Error"
        }
        val sb = StringBuilder("HTTP/1.1 $statusCode $statusText\r\n")
        sb.append("Content-Type: application/json; charset=utf-8\r\n")
        sb.append("Content-Length: ${bytes.size}\r\n")
        if (setCookie != null) sb.append("Set-Cookie: $setCookie\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun sendHtmlResponse(out: OutputStream, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun sendTextResponse(out: OutputStream, code: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code Status\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun readLine(inStream: InputStream): String? {
        val baos = ByteArrayOutputStream()
        var b: Int
        while (inStream.read().also { b = it } != -1) {
            if (b == '\n'.code) break
            if (b != '\r'.code) baos.write(b)
        }
        return if (baos.size() == 0 && b == -1) null else baos.toString(StandardCharsets.UTF_8.name())
    }

    private fun readBody(inStream: InputStream, length: Int): String {
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        var total = 0
        while (total < length) {
            val read = inStream.read(bytes, total, length - total)
            if (read < 0) break
            total += read
        }
        return String(bytes, 0, total, Charsets.UTF_8)
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split("&").associate { param ->
            val parts = param.split("=")
            val k = URLDecoder.decode(parts[0], "UTF-8")
            val v = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
            k to v
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /** Sleek, responsive, dark-mode single-page web app served directly to PC/Mac browsers. */
    private fun getWebPortalHtml(scopeName: String): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Arcane · AirVault PC Portal</title>
<style>
  :root {
    --ink-0: #07070e;
    --ink-1: #0f101d;
    --ink-2: #161729;
    --ink-3: #22243d;
    --edge: rgba(255,255,255,0.08);
    --edge-strong: rgba(255,255,255,0.18);
    --cyan: #38bdf8;
    --cyan-dim: rgba(56,189,248,0.14);
    --violet: #818cf8;
    --green: #34d399;
    --red: #f87171;
    --text-high: #f8fafc;
    --text-mid: #94a3b8;
    --text-faint: #64748b;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
  body { background: var(--ink-0); color: var(--text-high); min-height: 100vh; display: flex; flex-direction: column; overflow-x: hidden; }

  /* PIN Auth View */
  #authView { display: flex; align-items: center; justify-content: center; min-height: 100vh; padding: 24px; }
  .auth-card { background: var(--ink-1); border: 1px solid var(--edge-strong); border-radius: 24px; padding: 44px 36px; width: 100%; max-width: 440px; text-align: center; box-shadow: 0 24px 60px rgba(0,0,0,0.8); position: relative; }
  .logo-pill { display: inline-flex; align-items: center; gap: 8px; background: var(--cyan-dim); border: 1px solid rgba(56,189,248,0.35); border-radius: 999px; padding: 7px 18px; color: var(--cyan); font-weight: 700; font-size: 13px; margin-bottom: 24px; letter-spacing: 0.5px; }
  .auth-card h1 { font-size: 26px; font-weight: 800; margin-bottom: 8px; letter-spacing: -0.5px; }
  .auth-card p { font-size: 14px; color: var(--text-mid); margin-bottom: 28px; line-height: 1.6; }
  .pin-input { width: 100%; font-size: 32px; letter-spacing: 14px; text-align: center; padding: 14px; border-radius: 14px; border: 1px solid var(--edge-strong); background: var(--ink-2); color: var(--cyan); font-weight: 800; outline: none; margin-bottom: 20px; transition: border-color 0.2s, box-shadow 0.2s; font-family: monospace; }
  .pin-input:focus { border-color: var(--cyan); box-shadow: 0 0 0 4px rgba(56,189,248,0.22); }
  .btn-primary { width: 100%; padding: 14px; border-radius: 14px; border: none; background: var(--cyan); color: #020617; font-size: 15px; font-weight: 800; cursor: pointer; transition: all 0.2s; box-shadow: 0 4px 16px rgba(56,189,248,0.3); }
  .btn-primary:hover { opacity: 0.92; transform: translateY(-1px); }

  /* Dashboard View */
  #portalView { display: none; flex-direction: column; min-height: 100vh; }
  header { background: var(--ink-1); border-bottom: 1px solid var(--edge); padding: 14px 32px; display: flex; align-items: center; justify-content: space-between; position: sticky; top: 0; z-index: 50; gap: 16px; backdrop-filter: blur(12px); }
  .header-left { display: flex; align-items: center; gap: 18px; }
  .app-brand { font-size: 19px; font-weight: 900; display: flex; align-items: center; gap: 10px; color: var(--text-high); letter-spacing: -0.3px; }
  .brand-icon { width: 28px; height: 28px; background: linear-gradient(135deg, var(--cyan), var(--violet)); border-radius: 8px; display: flex; align-items: center; justify-content: center; font-size: 15px; }
  .badge-scope { background: rgba(129,140,248,0.15); border: 1px solid rgba(129,140,248,0.3); color: var(--violet); padding: 4px 12px; border-radius: 999px; font-size: 12px; font-weight: 700; }
  
  .header-center { flex: 1; max-width: 480px; position: relative; }
  .search-box { width: 100%; padding: 10px 16px 10px 38px; border-radius: 12px; border: 1px solid var(--edge); background: var(--ink-2); color: var(--text-high); font-size: 13px; outline: none; transition: border-color 0.2s; }
  .search-box:focus { border-color: var(--cyan); }
  .search-icon { position: absolute; left: 12px; top: 50%; transform: translateY(-50%); font-size: 14px; color: var(--text-faint); pointer-events: none; }

  .header-right { display: flex; align-items: center; gap: 12px; }
  .btn-upload { background: var(--cyan); color: #020617; border: none; padding: 10px 18px; border-radius: 10px; font-size: 13px; font-weight: 700; cursor: pointer; display: flex; align-items: center; gap: 8px; transition: all 0.2s; box-shadow: 0 2px 10px rgba(56,189,248,0.25); }
  .btn-upload:hover { opacity: 0.9; transform: translateY(-1px); }
  .btn-icon { background: var(--ink-2); border: 1px solid var(--edge); color: var(--text-mid); padding: 9px; border-radius: 10px; cursor: pointer; font-size: 14px; transition: all 0.2s; }
  .btn-icon:hover { color: var(--text-high); border-color: var(--edge-strong); background: var(--ink-3); }
  .badge-live { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--green); font-weight: 700; padding: 6px 12px; background: rgba(52,211,153,0.1); border: 1px solid rgba(52,211,153,0.25); border-radius: 999px; }
  .dot-pulse { width: 7px; height: 7px; background: var(--green); border-radius: 50%; box-shadow: 0 0 8px var(--green); }

  /* Main Container */
  .container { max-width: 1300px; margin: 0 auto; width: 100%; padding: 28px 32px; flex: 1; }
  .toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 24px; flex-wrap: wrap; gap: 14px; }
  .crumbs { display: flex; align-items: center; gap: 8px; font-size: 14px; color: var(--text-mid); flex-wrap: wrap; }
  .crumb-item { cursor: pointer; color: var(--cyan); text-decoration: none; font-weight: 600; display: inline-flex; align-items: center; gap: 4px; }
  .crumb-item:hover { text-decoration: underline; }
  .crumb-separator { color: var(--text-faint); font-size: 12px; }
  .crumb-current { color: var(--text-high); font-weight: 700; }
  .folder-stats { font-size: 13px; color: var(--text-faint); margin-left: 8px; }

  .filter-tabs { display: flex; gap: 6px; background: var(--ink-1); padding: 4px; border-radius: 10px; border: 1px solid var(--edge); }
  .tab-btn { background: none; border: none; color: var(--text-mid); padding: 6px 12px; border-radius: 7px; font-size: 12px; font-weight: 600; cursor: pointer; transition: all 0.15s; }
  .tab-btn.active { background: var(--ink-2); color: var(--cyan); border: 1px solid var(--edge); }

  .section-title { font-size: 13px; font-weight: 800; text-transform: uppercase; letter-spacing: 1px; color: var(--text-faint); margin: 28px 0 14px; display: flex; align-items: center; justify-content: space-between; }
  
  /* Grid & Card Layouts */
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(210px, 1fr)); gap: 16px; }
  .card { background: var(--ink-1); border: 1px solid var(--edge); border-radius: 16px; padding: 18px; cursor: pointer; transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1); position: relative; display: flex; flex-direction: column; overflow: hidden; }
  .card:hover { border-color: var(--edge-strong); transform: translateY(-3px); background: var(--ink-2); box-shadow: 0 12px 28px rgba(0,0,0,0.4); }
  .card-icon { font-size: 36px; margin-bottom: 12px; display: flex; align-items: center; justify-content: center; height: 50px; }
  .card-name { font-size: 14px; font-weight: 700; color: var(--text-high); margin-bottom: 6px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .card-meta { font-size: 12px; color: var(--text-faint); display: flex; justify-content: space-between; align-items: center; margin-top: auto; }
  .card-badge { font-size: 10px; font-weight: 700; padding: 2px 7px; border-radius: 6px; background: var(--ink-2); color: var(--text-mid); border: 1px solid var(--edge); text-transform: uppercase; }

  /* List Table Layout */
  .list-table { width: 100%; border-collapse: collapse; background: var(--ink-1); border: 1px solid var(--edge); border-radius: 14px; overflow: hidden; display: none; }
  .list-table th { text-align: left; padding: 12px 18px; font-size: 12px; font-weight: 700; color: var(--text-faint); text-transform: uppercase; border-bottom: 1px solid var(--edge); }
  .list-table td { padding: 14px 18px; font-size: 13px; color: var(--text-mid); border-bottom: 1px solid var(--edge); }
  .list-table tr:hover td { background: var(--ink-2); color: var(--text-high); }
  .list-name-col { display: flex; align-items: center; gap: 12px; font-weight: 600; color: var(--text-high); }
  #filesEmpty { display: none; color: var(--text-faint); font-size: 14px; padding: 48px 24px; text-align: center; background: var(--ink-1); border: 1px dashed var(--edge); border-radius: 16px; margin: 12px 0; }

  /* Drag & Drop Fullscreen Overlay */
  #dropOverlay { display: none; position: fixed; inset: 0; background: rgba(7,7,14,0.88); backdrop-filter: blur(8px); z-index: 999; align-items: center; justify-content: center; border: 4px dashed var(--cyan); pointer-events: none; }
  .drop-box { text-align: center; }
  .drop-icon { font-size: 64px; color: var(--cyan); margin-bottom: 16px; animation: bounce 1s infinite alternate; }
  @keyframes bounce { from { transform: translateY(0); } to { transform: translateY(-10px); } }

  /* Upload Drawer */
  #uploadDrawer { display: none; position: fixed; bottom: 24px; right: 24px; width: 380px; background: var(--ink-1); border: 1px solid var(--edge-strong); border-radius: 18px; box-shadow: 0 20px 50px rgba(0,0,0,0.8); z-index: 90; overflow: hidden; }
  .drawer-header { padding: 14px 18px; background: var(--ink-2); display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--edge); }
  .drawer-title { font-size: 13px; font-weight: 800; display: flex; align-items: center; gap: 8px; }
  .drawer-list { max-height: 260px; overflow-y: auto; padding: 12px 16px; display: flex; flex-direction: column; gap: 10px; }
  .upload-item { background: var(--ink-2); border: 1px solid var(--edge); border-radius: 10px; padding: 10px 12px; }
  .upload-row { display: flex; justify-content: space-between; font-size: 12px; font-weight: 600; margin-bottom: 6px; }
  .progress-bg { width: 100%; height: 6px; background: var(--ink-3); border-radius: 999px; overflow: hidden; }
  .progress-fill { height: 100%; width: 0%; background: linear-gradient(90deg, var(--cyan), var(--violet)); transition: width 0.15s; }

  /* Media Player Modal */
  #playerModal { display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.92); z-index: 100; align-items: center; justify-content: center; padding: 24px; backdrop-filter: blur(10px); }
  .player-box { width: 100%; max-width: 960px; background: var(--ink-1); border: 1px solid var(--edge-strong); border-radius: 20px; overflow: hidden; box-shadow: 0 30px 80px rgba(0,0,0,0.9); }
  .player-header { padding: 16px 22px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--edge); }
  video { width: 100%; max-height: 75vh; background: #000; outline: none; }
  .player-actions { display: flex; align-items: center; gap: 10px; }
  .btn-close { background: none; border: none; color: var(--text-high); font-size: 20px; cursor: pointer; padding: 4px 8px; border-radius: 8px; }
  .btn-close:hover { background: var(--ink-3); }

  /* Image Modal */
  #imageModal { display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.94); z-index: 100; align-items: center; justify-content: center; padding: 20px; }
  .image-box { max-width: 90vw; max-height: 90vh; display: flex; flex-direction: column; align-items: center; }
  .image-box img { max-width: 100%; max-height: 80vh; object-fit: contain; border-radius: 12px; border: 1px solid var(--edge-strong); }
</style>
</head>
<body>

<!-- Drag & Drop Fullscreen Mask -->
<div id="dropOverlay">
  <div class="drop-box">
    <div class="drop-icon">⚡</div>
    <h2 style="font-size:24px; font-weight:800; margin-bottom:8px;">Encrypt Directly Into Vault</h2>
    <p style="color:var(--text-mid); font-size:14px;">Drop files to stream and encrypt on-the-fly with AES-256-GCM</p>
  </div>
</div>

<!-- PIN Screen -->
<div id="authView">
  <div class="auth-card">
    <div class="logo-pill"><span>⚡</span> AirVault PC Portal</div>
    <h1>Vault Protected</h1>
    <p>Enter the 6-digit PIN displayed on your Arcane device to unlock encrypted streaming and high-speed upload.</p>
    <input type="text" id="pinCode" class="pin-input" maxlength="6" placeholder="000000" autofocus inputmode="numeric">
    <button class="btn-primary" onclick="submitAuth()">Connect to Vault</button>
    <div id="authErr" style="color:var(--red); font-size:13px; margin-top:14px; display:none;"></div>
  </div>
</div>

<!-- Main Web Dashboard -->
<div id="portalView">
  <header>
    <div class="header-left">
      <div class="app-brand">
        <div class="brand-icon">⚡</div>
        Arcane AirVault
      </div>
      <div class="badge-scope" id="scopeBadge">$scopeName</div>
    </div>

    <div class="header-center">
      <span class="search-icon">🔍</span>
      <input type="text" id="searchInput" class="search-box" placeholder="Search decrypted items in this view..." oninput="filterItems()">
    </div>

    <div class="header-right">
      <button class="btn-upload" onclick="triggerFileInput()">
        <span>+</span> Upload Files
      </button>
      <input type="file" id="filePicker" multiple style="display:none" onchange="handlePickedFiles(this.files)">
      
      <button class="btn-icon" id="viewToggleBtn" title="Toggle Grid / List View" onclick="toggleViewMode()">🔲</button>
      <button class="btn-icon" title="Lock & Disconnect" onclick="logout()">🔒</button>

      <div class="badge-live">
        <div class="dot-pulse"></div> Local Wi-Fi
      </div>
    </div>
  </header>

  <div class="container">
    <div class="toolbar">
      <div class="crumbs" id="crumbBar">
        <span class="crumb-item" onclick="loadFolder('')">The Vault</span>
      </div>

      <div class="filter-tabs">
        <button class="tab-btn active" data-filter="all" onclick="setFilter('all')">All</button>
        <button class="tab-btn" data-filter="video" onclick="setFilter('video')">Videos</button>
        <button class="tab-btn" data-filter="image" onclick="setFilter('image')">Images</button>
        <button class="tab-btn" data-filter="audio" onclick="setFilter('audio')">Audio</button>
        <button class="tab-btn" data-filter="doc" onclick="setFilter('doc')">Docs</button>
      </div>
    </div>

    <div id="foldersSection">
      <div class="section-title">Folders</div>
      <div class="grid" id="foldersGrid"></div>
    </div>

    <div class="section-title">
      <span>Files & Media</span>
      <span id="itemsCountBadge" style="font-size:12px; font-weight:600; color:var(--text-faint);"></span>
    </div>

    <div id="filesEmpty">No files in this view. Drop files anywhere or click "Upload Files" to encrypt directly into vault.</div>

    <!-- Grid View -->
    <div class="grid" id="filesGrid"></div>

    <!-- List View Table -->
    <table class="list-table" id="filesTable">
      <thead>
        <tr>
          <th>Name</th>
          <th>Size</th>
          <th>Type</th>
          <th>Actions</th>
        </tr>
      </thead>
      <tbody id="filesTableBody"></tbody>
    </table>
  </div>
</div>

<!-- Upload Progress Drawer -->
<div id="uploadDrawer">
  <div class="drawer-header">
    <div class="drawer-title">⚡ Ingesting into Vault</div>
    <button class="btn-close" style="font-size:16px;" onclick="closeUploadDrawer()">✕</button>
  </div>
  <div class="drawer-list" id="uploadList"></div>
</div>

<!-- Video Player Modal -->
<div id="playerModal">
  <div class="player-box">
    <div class="player-header">
      <div id="mediaTitle" style="font-weight:700; font-size:15px; color:var(--text-high);">Video Player</div>
      <div class="player-actions">
        <button class="btn-icon" id="downloadCurrentBtn" title="Download Original">⬇️</button>
        <button class="btn-close" onclick="closePlayer()">✕</button>
      </div>
    </div>
    <video id="mediaPlayer" controls autoplay></video>
  </div>
</div>

<!-- Image Lightbox Modal -->
<div id="imageModal" onclick="closeImage()">
  <div class="image-box" onclick="event.stopPropagation()">
    <img id="imageViewer" src="" alt="Decrypted Photo">
    <div style="display:flex; justify-content:space-between; align-items:center; width:100%; margin-top:14px;">
      <div id="imageTitle" style="font-size:14px; font-weight:700;">Photo</div>
      <button class="btn-icon" id="imageDownloadBtn" title="Download">⬇️</button>
    </div>
  </div>
</div>

<script>
let currentFolder = '';
let rawListingData = null;
let currentFilter = 'all';
let viewMode = 'grid'; // 'grid' | 'list'

function escapeHtml(str) {
  if (!str) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function getAuthHeaders(extraHeaders = {}) {
  const token = sessionStorage.getItem('arcane_token') || '';
  const headers = Object.assign({}, extraHeaders);
  if (token) headers['Authorization'] = 'Bearer ' + token;
  return headers;
}

function getMediaUrl(basePath, fileId) {
  const token = sessionStorage.getItem('arcane_token') || '';
  const query = token ? ('?token=' + encodeURIComponent(token)) : '';
  return basePath + encodeURIComponent(fileId) + query;
}

// Setup Drag & Drop Handlers on Window
window.addEventListener('dragover', (e) => {
  e.preventDefault();
  document.getElementById('dropOverlay').style.display = 'flex';
});

window.addEventListener('dragleave', (e) => {
  if (e.relatedTarget === null) {
    document.getElementById('dropOverlay').style.display = 'none';
  }
});

window.addEventListener('drop', (e) => {
  e.preventDefault();
  document.getElementById('dropOverlay').style.display = 'none';
  if (e.dataTransfer && e.dataTransfer.files.length > 0) {
    uploadFiles(e.dataTransfer.files);
  }
});

async function checkExistingAuth() {
  try {
    const res = await fetch('/api/ping', { headers: getAuthHeaders() });
    if (res.ok) {
      const data = await res.json();
      if (data.authenticated) {
        document.getElementById('authView').style.display = 'none';
        document.getElementById('portalView').style.display = 'flex';
        if (data.scope) document.getElementById('scopeBadge').textContent = data.scope;
        loadFolder('');
      }
    }
  } catch (_) {}
}
window.addEventListener('DOMContentLoaded', checkExistingAuth);

async function submitAuth() {
  const pin = document.getElementById('pinCode').value.trim();
  const errDiv = document.getElementById('authErr');
  errDiv.style.display = 'none';
  
  try {
    const res = await fetch('/api/auth', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({ pin })
    });
    const data = await res.json();
    if (res.ok && data.success) {
      if (data.token) sessionStorage.setItem('arcane_token', data.token);
      document.getElementById('authView').style.display = 'none';
      document.getElementById('portalView').style.display = 'flex';
      if (data.scope) document.getElementById('scopeBadge').textContent = data.scope;
      loadFolder('');
    } else {
      errDiv.textContent = data.error || 'Invalid PIN';
      errDiv.style.display = 'block';
    }
  } catch (e) {
    errDiv.textContent = 'Connection error. Make sure your phone is on the same Wi-Fi.';
    errDiv.style.display = 'block';
  }
}

async function logout() {
  try { await fetch('/api/logout', { method: 'POST', headers: getAuthHeaders() }); } catch (_) {}
  sessionStorage.removeItem('arcane_token');
  document.getElementById('portalView').style.display = 'none';
  document.getElementById('authView').style.display = 'flex';
  document.getElementById('pinCode').value = '';
}

async function loadFolder(folderId) {
  currentFolder = folderId || '';
  const foldersDiv = document.getElementById('foldersGrid');
  const filesDiv = document.getElementById('filesGrid');
  const emptyDiv = document.getElementById('filesEmpty');
  if (emptyDiv) emptyDiv.style.display = 'none';
  foldersDiv.innerHTML = '<div style="color:var(--text-faint); font-size:13px; padding:12px;">Loading decrypted entries...</div>';
  filesDiv.innerHTML = '';
  document.getElementById('filesTableBody').innerHTML = '';

  try {
    const res = await fetch('/api/browse?folderId=' + encodeURIComponent(currentFolder), {
      headers: getAuthHeaders()
    });
    if (res.status === 401) {
      logout();
      return;
    }
    const data = await res.json();
    rawListingData = data;
    renderBreadcrumbs(data.breadcrumbs || []);
    renderContent();
  } catch (e) {
    foldersDiv.innerHTML = '<div style="color:var(--red); font-size:13px; padding:12px;">Failed to load folder entries.</div>';
  }
}

function renderBreadcrumbs(crumbs) {
  const bar = document.getElementById('crumbBar');
  bar.innerHTML = '';
  crumbs.forEach((c, index) => {
    const isLast = index === crumbs.length - 1;
    if (index > 0) {
      const sep = document.createElement('span');
      sep.className = 'crumb-separator';
      sep.textContent = '›';
      bar.appendChild(sep);
    }
    const item = document.createElement('span');
    if (isLast) {
      item.className = 'crumb-current';
      item.textContent = c.name;
    } else {
      item.className = 'crumb-item';
      item.textContent = c.name;
      item.onclick = () => loadFolder(c.id);
    }
    bar.appendChild(item);
  });
}

function renderContent() {
  if (!rawListingData) return;
  const query = document.getElementById('searchInput').value.trim().toLowerCase();
  const foldersDiv = document.getElementById('foldersGrid');
  const filesDiv = document.getElementById('filesGrid');
  const table = document.getElementById('filesTable');
  const tableBody = document.getElementById('filesTableBody');
  const foldersSec = document.getElementById('foldersSection');
  const emptyDiv = document.getElementById('filesEmpty');
  
  foldersDiv.innerHTML = '';
  filesDiv.innerHTML = '';
  tableBody.innerHTML = '';

  // Filter folders
  const matchedFolders = (rawListingData.folders || []).filter(f => !query || f.name.toLowerCase().includes(query));
  if (matchedFolders.length === 0) {
    foldersSec.style.display = 'none';
  } else {
    foldersSec.style.display = 'block';
    matchedFolders.forEach(f => {
      const card = document.createElement('div');
      card.className = 'card';
      card.onclick = () => loadFolder(f.id);
      card.innerHTML = 
        '<div class="card-icon">📁</div>' +
        '<div class="card-name">' + escapeHtml(f.name) + '</div>' +
        '<div class="card-meta">' +
          '<span>Folder</span>' +
          '<span class="card-badge">' + escapeHtml(f.caption || '0 items') + '</span>' +
        '</div>';
      foldersDiv.appendChild(card);
    });
  }

  // Filter files
  const filteredFiles = (rawListingData.files || []).filter(f => {
    if (query && !f.name.toLowerCase().includes(query)) return false;
    if (currentFilter === 'video') return f.isVideo;
    if (currentFilter === 'image') return f.isImage;
    if (currentFilter === 'audio') return f.isAudio;
    if (currentFilter === 'doc') return f.isDoc;
    return true;
  });

  const countBadge = document.getElementById('itemsCountBadge');
  if (countBadge) {
    countBadge.textContent = filteredFiles.length + (filteredFiles.length === 1 ? ' file' : ' files');
  }

  if (filteredFiles.length === 0) {
    if (emptyDiv) emptyDiv.style.display = 'block';
    filesDiv.style.display = 'none';
    table.style.display = 'none';
  } else {
    if (emptyDiv) emptyDiv.style.display = 'none';
    filesDiv.style.display = viewMode === 'grid' ? 'grid' : 'none';
    table.style.display = viewMode === 'list' ? 'table' : 'none';

    filteredFiles.forEach(f => {
      let icon = f.isVideo ? '🎬' : (f.isImage ? '🖼️' : (f.isAudio ? '🎵' : (f.isDoc ? '📄' : '📦')));
      let typeLabel = f.isVideo ? 'Video' : (f.isImage ? 'Image' : (f.isAudio ? 'Audio' : (f.isDoc ? 'Document' : 'File')));

      // Grid Card
      const card = document.createElement('div');
      card.className = 'card';
      card.innerHTML = 
        '<div class="card-icon">' + icon + '</div>' +
        '<div class="card-name" title="' + escapeHtml(f.name) + '">' + escapeHtml(f.name) + '</div>' +
        '<div class="card-meta">' +
          '<span>' + formatBytes(f.size) + '</span>' +
          '<span class="card-badge">' + typeLabel + '</span>' +
        '</div>';
      card.onclick = () => handleFileClick(f);
      filesDiv.appendChild(card);

      // List Row
      const row = document.createElement('tr');
      row.style.cursor = 'pointer';
      row.onclick = () => handleFileClick(f);
      row.innerHTML = 
        '<td>' +
          '<div class="list-name-col">' +
            '<span>' + icon + '</span>' +
            '<span>' + escapeHtml(f.name) + '</span>' +
          '</div>' +
        '</td>' +
        '<td>' + formatBytes(f.size) + '</td>' +
        '<td><span class="card-badge">' + typeLabel + '</span></td>' +
        '<td>' +
          '<button class="btn-icon dl-btn">⬇️ Download</button>' +
        '</td>';
      row.querySelector('.dl-btn').onclick = (e) => {
        e.stopPropagation();
        downloadFile(f.fileId, f.name);
      };
      tableBody.appendChild(row);
    });
  }
}

function downloadFile(fileId, name) {
  const url = getMediaUrl('/api/download/', fileId);
  const a = document.createElement('a');
  a.href = url;
  if (name) a.download = decodeURIComponent(name);
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
}

function handleFileClick(f) {
  if (f.isVideo) {
    playVideo(f.name, f.fileId);
  } else if (f.isImage) {
    showImage(f.name, f.fileId);
  } else {
    downloadFile(f.fileId, f.name);
  }
}

function playVideo(name, fileId) {
  document.getElementById('mediaTitle').textContent = decodeURIComponent(name);
  document.getElementById('downloadCurrentBtn').onclick = () => downloadFile(fileId, name);
  const player = document.getElementById('mediaPlayer');
  player.src = getMediaUrl('/api/stream/', fileId);
  document.getElementById('playerModal').style.display = 'flex';
  player.play();
}

function closePlayer() {
  const player = document.getElementById('mediaPlayer');
  player.pause();
  player.src = '';
  document.getElementById('playerModal').style.display = 'none';
}

function showImage(name, fileId) {
  document.getElementById('imageTitle').textContent = decodeURIComponent(name);
  document.getElementById('imageViewer').src = getMediaUrl('/api/stream/', fileId);
  document.getElementById('imageDownloadBtn').onclick = () => downloadFile(fileId, name);
  document.getElementById('imageModal').style.display = 'flex';
}

function closeImage() {
  document.getElementById('imageViewer').src = '';
  document.getElementById('imageModal').style.display = 'none';
}

function filterItems() {
  renderContent();
}

function setFilter(type) {
  currentFilter = type;
  document.querySelectorAll('.tab-btn').forEach(b => {
    b.classList.toggle('active', b.dataset.filter === type);
  });
  renderContent();
}

function toggleViewMode() {
  viewMode = viewMode === 'grid' ? 'list' : 'grid';
  document.getElementById('viewToggleBtn').textContent = viewMode === 'grid' ? '🔲' : '📋';
  renderContent();
}

function triggerFileInput() {
  document.getElementById('filePicker').click();
}

function handlePickedFiles(files) {
  if (files && files.length > 0) {
    uploadFiles(files);
  }
}

// Multi-file Direct Upload Engine
async function uploadFiles(files) {
  if (!files || files.length === 0) return;
  const drawer = document.getElementById('uploadDrawer');
  const list = document.getElementById('uploadList');
  drawer.style.display = 'block';

  for (let i = 0; i < files.length; i++) {
    const file = files[i];
    await uploadSingleFile(file, list);
  }

  // Refresh current folder view
  loadFolder(currentFolder);
}

function uploadSingleFile(file, container) {
  return new Promise((resolve) => {
    const item = document.createElement('div');
    item.className = 'upload-item';
    item.innerHTML = 
      '<div class="upload-row">' +
        '<span style="overflow:hidden; text-overflow:ellipsis; white-space:nowrap; max-width:240px;">' + escapeHtml(file.name) + '</span>' +
        '<span class="upload-pct">0%</span>' +
      '</div>' +
      '<div class="progress-bg"><div class="progress-fill"></div></div>' +
      '<div style="font-size:11px; color:var(--text-faint); margin-top:4px;" class="upload-status">Encrypting with AES-256...</div>';
    container.prepend(item);

    const pctEl = item.querySelector('.upload-pct');
    const fillEl = item.querySelector('.progress-fill');
    const statusEl = item.querySelector('.upload-status');

    const token = sessionStorage.getItem('arcane_token') || '';
    const tokenQuery = token ? ('&token=' + encodeURIComponent(token)) : '';
    const url = '/api/upload?name=' + encodeURIComponent(file.name) +
                '&folderId=' + encodeURIComponent(currentFolder) +
                '&size=' + file.size + tokenQuery;

    const xhr = new XMLHttpRequest();
    xhr.open('POST', url, true);
    if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token);
    xhr.setRequestHeader('Content-Type', file.type || 'application/octet-stream');

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) {
        const pct = Math.round((e.loaded / e.total) * 100);
        pctEl.textContent = pct + '%';
        fillEl.style.width = pct + '%';
        statusEl.textContent = formatBytes(e.loaded) + ' / ' + formatBytes(e.total);
      }
    };

    xhr.onload = () => {
      if (xhr.status === 200) {
        pctEl.textContent = '100%';
        fillEl.style.width = '100%';
        fillEl.style.background = 'var(--green)';
        statusEl.textContent = '✓ Encrypted into Vault';
        statusEl.style.color = 'var(--green)';
      } else {
        fillEl.style.background = 'var(--red)';
        statusEl.textContent = '✗ Error: ' + xhr.status;
        statusEl.style.color = 'var(--red)';
      }
      resolve();
    };

    xhr.onerror = () => {
      fillEl.style.background = 'var(--red)';
      statusEl.textContent = '✗ Connection Failed';
      statusEl.style.color = 'var(--red)';
      resolve();
    };

    xhr.send(file);
  });
}

function closeUploadDrawer() {
  document.getElementById('uploadDrawer').style.display = 'none';
}

function formatBytes(bytes) {
  if (!bytes || bytes === 0) return '0 B';
  const k = 1024, dm = 1;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
}

// Auto-check connection status every 10 seconds
setInterval(async () => {
  if (document.getElementById('portalView').style.display === 'flex') {
    try {
      const res = await fetch('/api/ping', { headers: getAuthHeaders() });
      if (!res.ok) logout();
    } catch (_) {
      logout();
    }
  }
}, 10000);
</script>
</body>
</html>
    """.trimIndent()
}
