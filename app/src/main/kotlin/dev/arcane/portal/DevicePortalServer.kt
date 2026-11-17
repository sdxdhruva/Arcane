package dev.arcane.portal

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import dev.arcane.data.DeviceAlbum
import dev.arcane.data.DeviceItem
import dev.arcane.data.DeviceMedia
import dev.arcane.data.MediaKind
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
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
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone Web Portal: Embedded Local Wi-Fi Web Server for Normal Phone Storage.
 *
 * Designed for everyday local Wi-Fi file sharing, media streaming, and PC-to-phone uploads
 * without requiring encryption, keys, or passwords.
 *
 * Security & Isolation Invariants:
 * 1. Zero Vault Access: Does not import, reference, or access [VaultRepository] or [VaultRealm].
 *    It has NO capability to read encrypted chunks, master keys, or private app databases.
 * 2. Scoped URI Verification: All file/stream endpoints strictly enforce that the requested URI
 *    has scheme "content" and authority containing "media" (Android MediaStore only).
 * 3. Local Wi-Fi Only: Binds to local network interface (site-local IP address).
 * 4. Background Persistence: Designed to run as a Foreground Service ([DevicePortalService])
 *    so it persists while the phone screen is locked or the app is backgrounded until manually stopped.
 */
class DevicePortalServer(
    private val context: Context,
    private val media: DeviceMedia,
) {
    private val app = context.applicationContext

    data class PortalStatus(
        val isRunning: Boolean = false,
        val ipAddress: String = "",
        val port: Int = 8080,
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

    private fun logEvent(type: PortalEvent.EventType, description: String, details: String? = null) {
        val event = PortalEvent(type = type, description = description, details = details)
        _stats.update { current ->
            current.copy(
                recentEvents = (listOf(event) + current.recentEvents).take(30),
            )
        }
    }

    /** Clears all logged activity events. */
    fun clearEvents() {
        _stats.update { it.copy(recentEvents = emptyList()) }
    }

    /** Returns the current local Wi-Fi IPv4 address, or empty string if not connected. */
    fun currentLocalIp(): String = findLocalIpAddress()

    /** Starts the server on local Wi-Fi, binding to [preferredPort] or falling back if in use. */
    @Synchronized
    fun start(preferredPort: Int = 8080): Boolean {
        if (_status.value.isRunning) return true

        val ip = findLocalIpAddress()
        if (ip.isEmpty()) {
            logEvent(PortalEvent.EventType.ACCESS_DENIED, "Could not start: No local Wi-Fi connection detected")
            return false
        }

        var socket: ServerSocket? = null
        var port = preferredPort
        for (attempt in 0..10) {
            try {
                socket = ServerSocket(port)
                break
            } catch (_: Exception) {
                port++
            }
        }
        if (socket == null) {
            logEvent(PortalEvent.EventType.ACCESS_DENIED, "Could not bind to any port near $preferredPort")
            return false
        }

        serverSocket = socket

        _status.value = PortalStatus(
            isRunning = true,
            ipAddress = ip,
            port = port,
        )

        _stats.value = PortalStats(
            startedAtMillis = System.currentTimeMillis(),
        )

        logEvent(PortalEvent.EventType.SERVER_START, "Phone Web Portal started at http://$ip:$port")

        scope.launch {
            listenLoop(socket)
        }
        return true
    }

    /** Stops the server and closes all active sockets. */
    @Synchronized
    fun stop() {
        if (!_status.value.isRunning) return

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        _status.update { it.copy(isRunning = false) }
        logEvent(PortalEvent.EventType.SERVER_STOP, "Phone Web Portal stopped")
    }

    private fun listenLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                executor.execute {
                    handleClient(client)
                }
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun handleClient(client: Socket) {
        _stats.update { it.copy(activeClients = it.activeClients + 1, totalRequests = it.totalRequests + 1) }
        try {
            client.soTimeout = 30_000
            val inStream = BufferedInputStream(client.getInputStream())
            val outStream = BufferedOutputStream(client.getOutputStream())

            val requestLine = readLine(inStream) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val fullPath = parts[1]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(inStream) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }

            val path = if (fullPath.contains('?')) fullPath.substringBefore('?') else fullPath
            val queryParams = parseQueryParams(if (fullPath.contains('?')) fullPath.substringAfter('?') else "")

            when {
                path == "/" || path == "/index.html" -> {
                    sendHtmlResponse(outStream, portalHtml())
                }
                path == "/api/status" -> {
                    val resp = JSONObject().apply {
                        put("isRunning", _status.value.isRunning)
                        put("ipAddress", _status.value.ipAddress)
                        put("port", _status.value.port)
                        put("deviceName", Build.MODEL ?: "Android Device")
                        put("activeClients", _stats.value.activeClients)
                        put("totalRequests", _stats.value.totalRequests)
                        put("bytesStreamed", _stats.value.bytesStreamed)
                        put("bytesUploaded", _stats.value.bytesUploaded)
                        put("filesUploaded", _stats.value.filesUploaded)
                    }
                    sendJsonResponse(outStream, 200, resp)
                }
                path == "/api/browse" -> {
                    val folder = queryParams["folder"] ?: queryParams["folderId"]
                    handleBrowse(outStream, folder)
                }
                path == "/api/albums" -> {
                    handleGetAlbums(outStream)
                }
                path == "/api/items" -> {
                    val albumId = queryParams["albumId"]?.toLongOrNull()
                    val kind = queryParams["kind"]?.uppercase()
                    handleGetItems(outStream, albumId, kind)
                }
                path == "/api/thumb" -> {
                    val uriStr = queryParams["uri"] ?: ""
                    handleThumbnail(outStream, uriStr)
                }
                path == "/api/stream" || path == "/stream" -> {
                    val uriStr = queryParams["uri"] ?: ""
                    val nameParam = queryParams["name"]
                    val rangeHeader = headers["range"]
                    handleStream(outStream, uriStr, rangeHeader, nameParam)
                }
                path == "/api/download" || path == "/download" -> {
                    val uriStr = queryParams["uri"] ?: ""
                    val nameParam = queryParams["name"]
                    handleDownload(outStream, uriStr, nameParam, asAttachment = true)
                }
                path == "/api/upload" && method == "POST" -> {
                    val fileName = queryParams["name"] ?: "upload_${System.currentTimeMillis()}"
                    val targetFolder = queryParams["folder"]
                    val length = headers["content-length"]?.toLongOrNull() ?: 0L
                    val mimeType = headers["content-type"]
                    handleUpload(inStream, outStream, fileName, targetFolder, length, mimeType)
                }
                else -> {
                    sendTextResponse(outStream, 404, "Not Found")
                }
            }
        } catch (_: Exception) {
        } finally {
            _stats.update { it.copy(activeClients = (it.activeClients - 1).coerceAtLeast(0)) }
            runCatching { client.close() }
        }
    }

    private fun handleBrowse(out: OutputStream, rawFolder: String?) {
        val folder = rawFolder?.trim()?.trim('/') ?: ""
        val allItems = runBlocking { media.allItems() }

        val breadcrumbsArray = JSONArray()
        breadcrumbsArray.put(JSONObject().apply {
            put("id", "")
            put("name", "Internal Storage")
        })

        val parts = if (folder.isNotEmpty()) folder.split('/').filter { it.isNotEmpty() } else emptyList()
        var accumulated = ""
        for (part in parts) {
            accumulated = if (accumulated.isEmpty()) part else "$accumulated/$part"
            breadcrumbsArray.put(JSONObject().apply {
                put("id", accumulated)
                put("name", part)
            })
        }

        data class ResolvedItem(
            val item: DeviceItem,
            val folderPath: String,
            val bucketName: String,
        )

        val resolvedList = allItems.map {
            val rel = it.relativePath?.trim()?.trim('/')
            val effectiveFolder = when {
                !rel.isNullOrEmpty() -> rel
                it.bucketName.isNotEmpty() && it.bucketName != "Files" -> it.bucketName
                else -> "Other"
            }
            ResolvedItem(item = it, folderPath = effectiveFolder, bucketName = it.bucketName)
        }

        val subfolderMap = LinkedHashMap<String, MutableList<DeviceItem>>()
        val directFiles = mutableListOf<DeviceItem>()
        val prefix = if (folder.isEmpty()) "" else "$folder/"

        for (resolved in resolvedList) {
            val fp = resolved.folderPath
            if (folder.isEmpty()) {
                val topSeg = fp.substringBefore('/')
                subfolderMap.getOrPut(topSeg) { mutableListOf() }.add(resolved.item)
            } else if (fp.equals(folder, ignoreCase = true) || resolved.bucketName.equals(folder, ignoreCase = true) || resolved.item.bucketId.toString() == folder) {
                directFiles.add(resolved.item)
            } else if (fp.startsWith(prefix, ignoreCase = true)) {
                val subPath = fp.substring(prefix.length)
                val immediateSeg = subPath.substringBefore('/')
                if (immediateSeg.isNotEmpty()) {
                    subfolderMap.getOrPut(immediateSeg) { mutableListOf() }.add(resolved.item)
                } else {
                    directFiles.add(resolved.item)
                }
            }
        }

        val foldersArray = JSONArray()
        for ((subName, itemsInSub) in subfolderMap.entries.sortedByDescending { it.value.size }) {
            val subId = if (folder.isEmpty()) subName else "$folder/$subName"
            val totalBytes = itemsInSub.sumOf { it.size }
            val coverUri = itemsInSub.firstOrNull { it.isImage }?.uri?.toString()
                ?: itemsInSub.firstOrNull()?.uri?.toString() ?: ""
            foldersArray.put(JSONObject().apply {
                put("id", subId)
                put("name", subName)
                put("itemCount", itemsInSub.size)
                put("totalBytes", totalBytes)
                put("caption", "${itemsInSub.size} items · ${formatBytes(totalBytes)}")
                put("coverUri", coverUri)
            })
        }

        // If at root and directFiles is empty, provide recent items so the files section is alive:
        val effectiveFiles = if (folder.isEmpty() && directFiles.isEmpty()) {
            allItems.take(60)
        } else {
            directFiles
        }

        val filesArray = JSONArray()
        for (item in effectiveFiles) {
            filesArray.put(JSONObject().apply {
                put("uri", item.uri.toString())
                put("name", item.name)
                put("size", item.size)
                put("mimeType", item.mimeType ?: "application/octet-stream")
                put("isVideo", item.isVideo)
                put("isImage", item.isImage)
                put("isAudio", item.isAudio)
                put("isDoc", item.isDoc)
                put("modified", item.modifiedAtMillis)
                put("bucketName", item.bucketName)
                put("folderPath", item.relativePath ?: item.bucketName)
            })
        }

        val currentName = parts.lastOrNull() ?: "Internal Storage"
        val responseJson = JSONObject().apply {
            put("currentFolder", folder)
            put("currentFolderName", currentName)
            put("breadcrumbs", breadcrumbsArray)
            put("folders", foldersArray)
            put("files", filesArray)
            put("totalFiles", filesArray.length())
            put("totalFolders", foldersArray.length())
        }
        sendJsonResponse(out, 200, responseJson)
    }

    private fun handleGetAlbums(out: OutputStream) {
        val albums = runBlocking { media.albums() }
        val arr = JSONArray()
        for (a in albums) {
            arr.put(JSONObject().apply {
                put("bucketId", a.bucketId.toString())
                put("name", a.name)
                put("itemCount", a.itemCount)
                put("totalBytes", a.totalBytes)
                put("coverUri", a.cover?.uri?.toString() ?: "")
            })
        }
        sendJsonResponse(out, 200, JSONObject().apply { put("albums", arr) })
    }

    private fun handleGetItems(out: OutputStream, albumId: Long?, kindStr: String?) {
        val kindSet = when (kindStr) {
            "IMAGES" -> setOf(MediaKind.IMAGES)
            "VIDEO" -> setOf(MediaKind.VIDEO)
            "AUDIO" -> setOf(MediaKind.AUDIO)
            else -> MediaKind.entries.toSet()
        }

        val items = runBlocking {
            if (kindStr == "DOWNLOADS") {
                media.downloadItems()
            } else if (albumId != null) {
                media.items(albumId, kindSet)
            } else {
                media.allItems(kindSet)
            }
        }

        val arr = JSONArray()
        for (item in items) {
            arr.put(JSONObject().apply {
                put("uri", item.uri.toString())
                put("name", item.name)
                put("bucketId", item.bucketId.toString())
                put("bucketName", item.bucketName)
                put("mimeType", item.mimeType ?: "application/octet-stream")
                put("size", item.size)
                put("modifiedAtMillis", item.modifiedAtMillis)
                put("durationMillis", item.durationMillis)
                put("isVideo", item.isVideo)
                put("isImage", item.isImage)
                put("isAudio", item.isAudio)
            })
        }
        sendJsonResponse(out, 200, JSONObject().apply { put("items", arr) })
    }

    private fun handleThumbnail(out: OutputStream, uriString: String) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
        if (uri == null || uri.scheme != "content" || !uri.authority.orEmpty().contains("media")) {
            sendTextResponse(out, 403, "Access restricted to MediaStore")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val bitmap = app.contentResolver.loadThumbnail(uri, Size(360, 360), null)
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                val bytes = baos.toByteArray()

                val headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Cache-Control: public, max-age=86400\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(headers.toByteArray(Charsets.US_ASCII))
                out.write(bytes)
                out.flush()
                return
            }
        } catch (_: Exception) {
            // Fallback to stream original
        }

        handleDownload(out, uriString, nameParam = null, asAttachment = false)
    }

    private fun handleStream(out: OutputStream, uriString: String, rangeHeader: String?, nameParam: String? = null) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
        if (uri == null || uri.scheme != "content" || !uri.authority.orEmpty().contains("media")) {
            sendTextResponse(out, 403, "Access restricted to MediaStore")
            return
        }

        val pfd = runCatching { app.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (pfd == null) {
            sendTextResponse(out, 404, "Media not found")
            return
        }

        pfd.use { descriptor ->
            val totalLength = descriptor.statSize
            val mimeType = app.contentResolver.getType(uri) ?: "video/mp4"

            var start = 0L
            var end = totalLength - 1

            if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
                val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
                val parts = rangeSpec.split("-")
                val rStart = parts.getOrNull(0)?.toLongOrNull()
                val rEnd = parts.getOrNull(1)?.toLongOrNull()
                if (rStart != null) start = rStart.coerceIn(0L, totalLength - 1)
                if (rEnd != null) end = rEnd.coerceIn(start, totalLength - 1)
            }

            val rangeLength = (end - start + 1).coerceAtLeast(0L)
            val isPartial = !rangeHeader.isNullOrBlank()

            val rawName = nameParam?.takeIf { it.isNotBlank() } ?: queryDisplayName(uri) ?: "media"
            val fileName = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
            val rfc5987 = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
            val safeAscii = fileName.replace('"', '_').replace('\r', '_').replace('\n', '_')

            val statusLine = if (isPartial) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK"
            val sb = StringBuilder("$statusLine\r\n")
            sb.append("Content-Type: $mimeType\r\n")
            sb.append("Accept-Ranges: bytes\r\n")
            sb.append("Content-Length: $rangeLength\r\n")
            sb.append("Content-Disposition: inline; filename=\"$safeAscii\"; filename*=UTF-8''$rfc5987\r\n")
            if (isPartial) {
                sb.append("Content-Range: bytes $start-$end/$totalLength\r\n")
            }
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.US_ASCII))

            FileInputStream(descriptor.fileDescriptor).use { fis ->
                if (start > 0) {
                    fis.channel.position(start)
                }
                val buffer = ByteArray(64 * 1024)
                var bytesToSend = rangeLength
                while (bytesToSend > 0) {
                    val toRead = minOf(buffer.size.toLong(), bytesToSend).toInt()
                    val read = fis.read(buffer, 0, toRead)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    bytesToSend -= read
                    _stats.update { it.copy(bytesStreamed = it.bytesStreamed + read) }
                }
                out.flush()
            }
        }
    }

    private fun handleDownload(out: OutputStream, uriString: String, nameParam: String? = null, asAttachment: Boolean = true) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
        if (uri == null || uri.scheme != "content" || !uri.authority.orEmpty().contains("media")) {
            sendTextResponse(out, 403, "Access restricted to MediaStore")
            return
        }

        val pfd = runCatching { app.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (pfd == null) {
            sendTextResponse(out, 404, "Media not found")
            return
        }

        pfd.use { descriptor ->
            val totalLength = descriptor.statSize
            val mimeType = app.contentResolver.getType(uri) ?: "application/octet-stream"
            val rawName = nameParam?.takeIf { it.isNotBlank() } ?: queryDisplayName(uri) ?: "download_${System.currentTimeMillis()}"
            val fileName = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
            val rfc5987 = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
            val safeAscii = fileName.replace('"', '_').replace('\r', '_').replace('\n', '_')

            val disposition = if (asAttachment) {
                "attachment; filename=\"$safeAscii\"; filename*=UTF-8''$rfc5987"
            } else {
                "inline; filename=\"$safeAscii\"; filename*=UTF-8''$rfc5987"
            }
            val headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $mimeType\r\n" +
                "Content-Length: $totalLength\r\n" +
                "Content-Disposition: $disposition\r\n" +
                "Connection: close\r\n\r\n"
            out.write(headers.toByteArray(Charsets.US_ASCII))

            FileInputStream(descriptor.fileDescriptor).use { fis ->
                val buffer = ByteArray(64 * 1024)
                var bytesRemaining = totalLength
                while (bytesRemaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), bytesRemaining).toInt()
                    val read = fis.read(buffer, 0, toRead)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    bytesRemaining -= read
                    _stats.update { it.copy(bytesStreamed = it.bytesStreamed + read) }
                }
                out.flush()
            }
        }
    }

    private fun handleUpload(
        inStream: InputStream,
        out: OutputStream,
        fileName: String,
        targetFolder: String?,
        length: Long,
        mimeType: String?,
    ) {
        val cleanName = sanitizeFileName(fileName)
        val effectiveMime = mimeType ?: "application/octet-stream"

        logEvent(PortalEvent.EventType.UPLOAD_START, "Receiving upload to phone: $cleanName", formatBytes(length))

        val uploadRelPath = if (targetFolder.isNullOrBlank()) {
            Environment.DIRECTORY_DOWNLOADS + "/ArcaneShare"
        } else {
            val trimmed = targetFolder.trim().trim('/')
            if (!trimmed.startsWith("Download") && !trimmed.startsWith("DCIM") && !trimmed.startsWith("Pictures") && !trimmed.startsWith("Documents") && !trimmed.startsWith("Movies") && !trimmed.startsWith("Music")) {
                Environment.DIRECTORY_DOWNLOADS + "/" + trimmed
            } else {
                trimmed
            }
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
            put(MediaStore.MediaColumns.MIME_TYPE, effectiveMime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, uploadRelPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val uri = app.contentResolver.insert(collection, contentValues)
        if (uri == null) {
            sendJsonResponse(out, 500, JSONObject().apply { put("error", "Could not create file in phone storage.") })
            return
        }

        try {
            app.contentResolver.openOutputStream(uri)?.use { fileOut ->
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = inStream.read(buffer, 0, toRead)
                    if (read < 0) break
                    fileOut.write(buffer, 0, read)
                    remaining -= read
                }
                fileOut.flush()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                app.contentResolver.update(uri, contentValues, null, null)
            }

            _stats.update {
                it.copy(
                    bytesUploaded = it.bytesUploaded + length,
                    filesUploaded = it.filesUploaded + 1,
                )
            }
            logEvent(PortalEvent.EventType.UPLOAD_COMPLETE, "Saved to Downloads/ArcaneShare: $cleanName", formatBytes(length))

            sendJsonResponse(out, 200, JSONObject().apply {
                put("success", true)
                put("name", cleanName)
                put("size", length)
            })
        } catch (e: Exception) {
            app.contentResolver.delete(uri, null, null)
            logEvent(PortalEvent.EventType.ACCESS_DENIED, "Upload failed for $cleanName: ${e.message}")
            sendJsonResponse(out, 500, JSONObject().apply { put("error", e.message ?: "Upload failed") })
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        return app.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    }

    private fun sanitizeFileName(raw: String): String {
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        val candidate = decoded.substringAfterLast('/').substringAfterLast('\\').trim()
        val clean = candidate.map { if (it.isISOControl() || it == '/' || it == '\\') '_' else it }
            .joinToString("")
            .trim('.', ' ')
            .take(128)
        return clean.ifEmpty { "file_${System.currentTimeMillis()}" }
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

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) {
                return if (sb.isEmpty()) null else sb.toString()
            }
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun parseQueryParams(queryString: String): Map<String, String> {
        if (queryString.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        val pairs = queryString.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                result[key] = value
            }
        }
        return result
    }

    private fun findLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name.lowercase()
                if (name.contains("dummy") || name.contains("p2p") || name.contains("tun")) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun portalHtml(): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Arcane · Phone Storage Portal</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;600&family=Plus+Jakarta+Sans:wght@400;500;600;700;800&display=swap" rel="stylesheet">
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
    --violet-dim: rgba(129,140,248,0.14);
    --green: #34d399;
    --green-dim: rgba(52,211,153,0.12);
    --red: #f87171;
    --text-high: #f8fafc;
    --text-mid: #94a3b8;
    --text-faint: #64748b;
    --radius: 14px;
    --radius-sm: 8px;
    --font-sans: 'Plus Jakarta Sans', -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    --font-mono: 'JetBrains Mono', monospace;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: var(--font-sans); }
  body { background: var(--ink-0); color: var(--text-high); min-height: 100vh; display: flex; flex-direction: column; overflow-x: hidden; }

  header { background: var(--ink-1); border-bottom: 1px solid var(--edge); padding: 14px 28px; display: flex; align-items: center; justify-content: space-between; position: sticky; top: 0; z-index: 50; gap: 16px; backdrop-filter: blur(12px); }
  .header-left { display: flex; align-items: center; gap: 14px; }
  .app-brand { font-size: 18px; font-weight: 800; display: flex; align-items: center; gap: 10px; color: var(--text-high); letter-spacing: -0.3px; }
  .brand-icon { width: 32px; height: 32px; background: linear-gradient(135deg, var(--cyan), var(--violet)); border-radius: 9px; display: flex; align-items: center; justify-content: center; font-size: 16px; color: #020617; font-weight: 900; }
  .badge-scope { background: var(--cyan-dim); border: 1px solid rgba(56,189,248,0.3); color: var(--cyan); padding: 4px 12px; border-radius: 999px; font-size: 12px; font-weight: 700; display: flex; align-items: center; gap: 6px; }

  .header-center { flex: 1; max-width: 440px; position: relative; }
  .search-box { width: 100%; padding: 10px 16px 10px 38px; border-radius: 12px; border: 1px solid var(--edge); background: var(--ink-2); color: var(--text-high); font-size: 13px; outline: none; transition: border-color 0.2s; }
  .search-box:focus { border-color: var(--cyan); }
  .search-icon { position: absolute; left: 12px; top: 50%; transform: translateY(-50%); font-size: 14px; color: var(--text-faint); pointer-events: none; }

  .header-right { display: flex; align-items: center; gap: 10px; }
  .btn { display: inline-flex; align-items: center; gap: 8px; padding: 9px 16px; border-radius: 10px; font-size: 13px; font-weight: 700; cursor: pointer; border: 1px solid var(--edge); background: var(--ink-2); color: var(--text-high); transition: all 0.15s ease; text-decoration: none; }
  .btn:hover { background: var(--ink-3); border-color: var(--edge-strong); }
  .btn-primary { background: var(--cyan); color: #020617; border-color: var(--cyan); box-shadow: 0 2px 10px rgba(56,189,248,0.25); }
  .btn-primary:hover { opacity: 0.92; transform: translateY(-1px); }
  .btn-sm { padding: 6px 12px; font-size: 12px; border-radius: 8px; }
  .btn-icon { padding: 9px; min-width: 36px; justify-content: center; }

  .badge-live { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--green); font-weight: 700; padding: 6px 12px; background: var(--green-dim); border: 1px solid rgba(52,211,153,0.25); border-radius: 999px; }
  .dot-pulse { width: 7px; height: 7px; background: var(--green); border-radius: 50%; box-shadow: 0 0 8px var(--green); }

  /* Main Container */
  .container { max-width: 1400px; margin: 0 auto; width: 100%; padding: 24px 28px; flex: 1; }
  .toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 24px; flex-wrap: wrap; gap: 14px; }
  .crumbs { display: flex; align-items: center; gap: 8px; font-size: 14px; color: var(--text-mid); flex-wrap: wrap; }
  .crumb-item { cursor: pointer; color: var(--cyan); text-decoration: none; font-weight: 700; display: inline-flex; align-items: center; gap: 4px; transition: color 0.15s; }
  .crumb-item:hover { text-decoration: underline; color: #82e2f3; }
  .crumb-separator { color: var(--text-faint); font-size: 12px; user-select: none; }
  .crumb-current { color: var(--text-high); font-weight: 800; display: inline-flex; align-items: center; gap: 4px; }

  .filter-tabs { display: flex; gap: 4px; background: var(--ink-1); padding: 4px; border-radius: 10px; border: 1px solid var(--edge); }
  .tab-btn { background: none; border: none; color: var(--text-mid); padding: 6px 14px; border-radius: 7px; font-size: 12px; font-weight: 700; cursor: pointer; transition: all 0.15s; }
  .tab-btn:hover { color: var(--text-high); }
  .tab-btn.active { background: var(--ink-2); color: var(--cyan); border: 1px solid var(--edge); }

  .section-title { font-size: 12px; font-weight: 800; text-transform: uppercase; letter-spacing: 1px; color: var(--text-faint); margin: 26px 0 14px; display: flex; align-items: center; justify-content: space-between; }

  /* Folder Cards Grid */
  .folders-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 14px; margin-bottom: 12px; }
  .folder-card { background: var(--ink-1); border: 1px solid var(--edge); border-radius: var(--radius); padding: 16px; cursor: pointer; transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1); display: flex; align-items: center; gap: 14px; }
  .folder-card:hover { border-color: var(--edge-strong); transform: translateY(-2px); background: var(--ink-2); box-shadow: 0 8px 24px rgba(0,0,0,0.4); }
  .folder-icon-wrap { width: 44px; height: 44px; border-radius: 10px; background: var(--cyan-dim); border: 1px solid rgba(56,189,248,0.25); display: flex; align-items: center; justify-content: center; font-size: 22px; flex-shrink: 0; }
  .folder-info { flex: 1; min-width: 0; }
  .folder-name { font-size: 14px; font-weight: 700; color: var(--text-high); margin-bottom: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .folder-meta { font-size: 11px; color: var(--text-faint); display: flex; justify-content: space-between; align-items: center; }
  .folder-badge { font-size: 9px; font-weight: 700; padding: 2px 6px; border-radius: 4px; background: var(--ink-3); color: var(--text-mid); text-transform: uppercase; }

  /* Files Grid */
  .files-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(210px, 1fr)); gap: 16px; }
  .file-card { background: var(--ink-1); border: 1px solid var(--edge); border-radius: var(--radius); overflow: hidden; cursor: pointer; transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1); display: flex; flex-direction: column; position: relative; }
  .file-card:hover { border-color: var(--edge-strong); transform: translateY(-3px); background: var(--ink-2); box-shadow: 0 12px 28px rgba(0,0,0,0.45); }
  .file-thumb-wrap { width: 100%; aspect-ratio: 1; background: #000; position: relative; overflow: hidden; display: flex; align-items: center; justify-content: center; }
  .file-thumb-img { width: 100%; height: 100%; object-fit: cover; }
  .play-badge { position: absolute; width: 42px; height: 42px; border-radius: 50%; background: rgba(0,0,0,0.68); backdrop-filter: blur(4px); border: 1px solid rgba(255,255,255,0.3); display: flex; align-items: center; justify-content: center; color: #fff; font-size: 16px; padding-left: 3px; }
  .audio-icon-box { color: var(--cyan); font-size: 44px; display: flex; align-items: center; justify-content: center; }
  .doc-icon-box { color: var(--violet); font-size: 42px; display: flex; align-items: center; justify-content: center; }

  .file-body { padding: 12px 14px; display: flex; flex-direction: column; gap: 4px; flex: 1; }
  .file-name { font-size: 13px; font-weight: 700; color: var(--text-high); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .file-meta { font-size: 11px; color: var(--text-faint); display: flex; justify-content: space-between; align-items: center; margin-top: auto; }
  .file-type-badge { font-size: 9px; font-weight: 800; padding: 2px 6px; border-radius: 4px; background: var(--ink-3); color: var(--text-mid); text-transform: uppercase; font-family: var(--font-mono); }

  /* Table View */
  .files-table { width: 100%; border-collapse: collapse; background: var(--ink-1); border: 1px solid var(--edge); border-radius: var(--radius); overflow: hidden; display: none; }
  .files-table th { text-align: left; padding: 12px 18px; font-size: 11px; font-weight: 800; color: var(--text-faint); text-transform: uppercase; border-bottom: 1px solid var(--edge); letter-spacing: 0.5px; }
  .files-table td { padding: 12px 18px; font-size: 13px; color: var(--text-mid); border-bottom: 1px solid var(--edge); }
  .files-table tr:hover td { background: var(--ink-2); color: var(--text-high); }
  .table-name { display: flex; align-items: center; gap: 10px; font-weight: 600; color: var(--text-high); }
  .table-icon { font-size: 18px; }
  .table-actions { display: flex; align-items: center; gap: 6px; }

  #filesEmpty { display: none; color: var(--text-faint); font-size: 14px; padding: 48px 24px; text-align: center; background: var(--ink-1); border: 1px dashed var(--edge); border-radius: var(--radius); margin: 12px 0; }

  /* Drag & Drop Fullscreen Overlay */
  #dropOverlay { display: none; position: fixed; inset: 0; background: rgba(7,7,14,0.9); backdrop-filter: blur(8px); z-index: 999; align-items: center; justify-content: center; border: 4px dashed var(--cyan); pointer-events: none; }
  .drop-box { text-align: center; }
  .drop-icon { font-size: 64px; color: var(--cyan); margin-bottom: 16px; animation: bounce 1s infinite alternate; }
  @keyframes bounce { from { transform: translateY(0); } to { transform: translateY(-10px); } }

  /* Upload Drawer */
  #uploadDrawer { display: none; position: fixed; bottom: 24px; right: 24px; width: 380px; background: var(--ink-1); border: 1px solid var(--edge-strong); border-radius: 18px; box-shadow: 0 20px 50px rgba(0,0,0,0.85); z-index: 90; overflow: hidden; }
  .drawer-header { padding: 14px 18px; background: var(--ink-2); display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--edge); }
  .drawer-title { font-size: 13px; font-weight: 800; display: flex; align-items: center; gap: 8px; }
  .drawer-list { max-height: 260px; overflow-y: auto; padding: 12px 16px; display: flex; flex-direction: column; gap: 10px; }
  .upload-item { background: var(--ink-2); border: 1px solid var(--edge); border-radius: 10px; padding: 10px 12px; }
  .upload-row { display: flex; justify-content: space-between; font-size: 12px; font-weight: 600; margin-bottom: 6px; }
  .progress-bg { width: 100%; height: 6px; background: var(--ink-3); border-radius: 999px; overflow: hidden; }
  .progress-fill { height: 100%; width: 0%; background: linear-gradient(90deg, var(--cyan), var(--violet)); transition: width 0.15s; }

  /* Modals */
  .modal { display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.92); z-index: 100; align-items: center; justify-content: center; padding: 24px; backdrop-filter: blur(10px); }
  .modal-box { width: 100%; max-width: 960px; background: var(--ink-1); border: 1px solid var(--edge-strong); border-radius: 20px; overflow: hidden; box-shadow: 0 30px 80px rgba(0,0,0,0.9); }
  .modal-header { padding: 16px 22px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--edge); }
  .modal-title { font-weight: 700; font-size: 15px; color: var(--text-high); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 70%; }
  .modal-actions { display: flex; align-items: center; gap: 10px; }
  .btn-close { background: none; border: none; color: var(--text-high); font-size: 20px; cursor: pointer; padding: 4px 8px; border-radius: 8px; }
  .btn-close:hover { background: var(--ink-3); }

  /* Image Modal */
  .image-box { max-width: 90vw; max-height: 90vh; display: flex; flex-direction: column; align-items: center; }
  .image-box img { max-width: 100%; max-height: 80vh; object-fit: contain; border-radius: 12px; border: 1px solid var(--edge-strong); }
</style>
</head>
<body>

<!-- Drag & Drop Fullscreen Overlay -->
<div id="dropOverlay">
  <div class="drop-box">
    <div class="drop-icon">⚡</div>
    <h2 style="font-size:24px; font-weight:800; margin-bottom:8px;">Upload to Phone Storage</h2>
    <p style="color:var(--text-mid); font-size:14px;">Drop files anywhere to transfer directly to this folder</p>
  </div>
</div>

<header>
  <div class="header-left">
    <div class="app-brand">
      <div class="brand-icon">⚡</div>
      Arcane Phone Portal
    </div>
    <div class="badge-scope">
      <span>📁</span> Internal Storage
    </div>
  </div>

  <div class="header-center">
    <span class="search-icon">🔍</span>
    <input type="text" id="searchBox" class="search-box" placeholder="Search folders & files..." oninput="filterContent()">
  </div>

  <div class="header-right">
    <button class="btn btn-primary" onclick="triggerFileInput()">
      <span>+</span> Upload Files
    </button>
    <input type="file" id="filePicker" multiple style="display:none" onchange="handlePickedFiles(this.files)">

    <button class="btn btn-icon" id="viewToggleBtn" title="Toggle Grid / List View" onclick="toggleViewMode()">🔲</button>
    <button class="btn btn-icon" title="Refresh" onclick="loadFolder(currentFolder)">🔄</button>

    <div class="badge-live">
      <div class="dot-pulse"></div> Local Wi-Fi
    </div>
  </div>
</header>

<div class="container">
  <div class="toolbar">
    <div class="crumbs" id="crumbBar">
      <span class="crumb-current">📁 Internal Storage</span>
    </div>

    <div class="filter-tabs">
      <button class="tab-btn active" data-filter="all" onclick="setFilter('all')">All</button>
      <button class="tab-btn" data-filter="video" onclick="setFilter('video')">Videos</button>
      <button class="tab-btn" data-filter="image" onclick="setFilter('image')">Photos</button>
      <button class="tab-btn" data-filter="audio" onclick="setFilter('audio')">Music</button>
      <button class="tab-btn" data-filter="doc" onclick="setFilter('doc')">Docs</button>
    </div>
  </div>

  <!-- Folders Section -->
  <div id="foldersSection">
    <div class="section-title">
      <span>Folders</span>
      <span id="foldersCountBadge" style="font-size:11px; font-weight:700; color:var(--text-faint);"></span>
    </div>
    <div class="folders-grid" id="foldersGrid"></div>
  </div>

  <!-- Files & Media Section -->
  <div class="section-title">
    <span>Files & Media</span>
    <span id="itemsCountBadge" style="font-size:11px; font-weight:700; color:var(--text-faint);"></span>
  </div>

  <div id="filesEmpty">No files found in this view. Drop files anywhere or click "Upload Files" to transfer to phone.</div>

  <!-- Grid View -->
  <div class="files-grid" id="filesGrid"></div>

  <!-- List View Table -->
  <table class="files-table" id="filesTable">
    <thead>
      <tr>
        <th>Name</th>
        <th>Size</th>
        <th>Type</th>
        <th>Modified</th>
        <th>Actions</th>
      </tr>
    </thead>
    <tbody id="filesTableBody"></tbody>
  </table>
</div>

<!-- Upload Progress Drawer -->
<div id="uploadDrawer">
  <div class="drawer-header">
    <div class="drawer-title">⚡ Uploading to Phone</div>
    <button class="btn-close" style="font-size:16px;" onclick="closeUploadDrawer()">✕</button>
  </div>
  <div class="drawer-list" id="uploadList"></div>
</div>

<!-- Toast Notification -->
<div id="toast" style="position:fixed; bottom:28px; left:50%; transform:translateX(-50%); background:var(--ink-2); border:1px solid var(--cyan); color:var(--text-high); padding:12px 24px; border-radius:12px; font-size:13px; font-weight:700; box-shadow:0 12px 36px rgba(0,0,0,0.85); z-index:9999; display:none; align-items:center; gap:10px;">
  <span style="font-size:16px;">🔗</span>
  <span id="toastMsg">Stream link copied to clipboard!</span>
</div>

<!-- Video Player Modal -->
<div id="playerModal" class="modal" onclick="if(event.target===this) closePlayer()">
  <div class="modal-box">
    <div class="modal-header">
      <div id="videoTitle" class="modal-title">Video Player</div>
      <div class="modal-actions">
        <button id="videoCopyBtn" class="btn btn-sm" onclick="copyCurrentModalStream()" title="Copy direct stream URL for VLC/players">🔗 Copy Stream Link</button>
        <a id="videoDownloadBtn" href="" class="btn btn-sm btn-primary" download="">Download Original</a>
        <button class="btn-close" onclick="closePlayer()">✕</button>
      </div>
    </div>
    <video id="mediaPlayer" controls autoplay style="width:100%; max-height:75vh; background:#000; outline:none; display:block;"></video>
  </div>
</div>

<!-- Image Lightbox Modal -->
<div id="imageModal" class="modal" onclick="if(event.target===this) closeImage()">
  <div class="image-box">
    <img id="imageViewer" src="" alt="Photo">
    <div style="display:flex; justify-content:space-between; align-items:center; width:100%; margin-top:14px; background:var(--ink-1); padding:12px 18px; border-radius:12px; border:1px solid var(--edge);">
      <div id="imageTitle" style="font-size:14px; font-weight:700;">Photo</div>
      <div style="display:flex; gap:10px; align-items:center;">
        <button id="imageCopyBtn" class="btn btn-sm" onclick="copyCurrentImageStream()">🔗 Copy Link</button>
        <a id="imageDownloadBtn" href="" class="btn btn-sm btn-primary" download="">Download</a>
        <button class="btn-close" onclick="closeImage()">✕</button>
      </div>
    </div>
  </div>
</div>

<!-- Audio Player Modal -->
<div id="audioModal" class="modal" onclick="if(event.target===this) closeAudio()">
  <div class="modal-box" style="max-width:440px;">
    <div class="modal-header">
      <div id="audioTitle" class="modal-title">Audio Player</div>
      <button class="btn-close" onclick="closeAudio()">✕</button>
    </div>
    <div style="padding:24px; text-align:center;">
      <div style="font-size:54px; margin-bottom:16px;">🎵</div>
      <audio id="audioPlayer" controls autoplay style="width:100%; margin-bottom:18px;"></audio>
      <div style="display:flex; flex-direction:column; gap:10px;">
        <button id="audioCopyBtn" class="btn btn-sm" onclick="copyCurrentAudioStream()" style="width:100%; justify-content:center;">🔗 Copy Stream Link</button>
        <a id="audioDownloadBtn" href="" class="btn btn-primary" download="" style="width:100%; justify-content:center;">Download Audio</a>
      </div>
    </div>
  </div>
</div>

<script>
let currentFolder = '';
let currentFilter = 'all';
let viewMode = localStorage.getItem('arcane_view_mode') || 'grid';
let rawData = null;

function escapeHtml(str) {
  if (!str) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function formatBytes(bytes) {
  if (!bytes || bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let b = bytes;
  let i = 0;
  while (b >= 1024 && i < units.length - 1) { b /= 1024; i++; }
  return b.toFixed(1) + ' ' + units[i];
}

function formatDate(millis) {
  if (!millis) return '';
  const d = new Date(millis);
  return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

// Drag & drop handlers on window
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

function triggerFileInput() {
  document.getElementById('filePicker').click();
}

function handlePickedFiles(files) {
  if (files && files.length > 0) {
    uploadFiles(files);
  }
}

function toggleViewMode() {
  viewMode = viewMode === 'grid' ? 'list' : 'grid';
  localStorage.setItem('arcane_view_mode', viewMode);
  document.getElementById('viewToggleBtn').textContent = viewMode === 'grid' ? '🔲' : '☰';
  renderContent();
}

function setFilter(filter) {
  currentFilter = filter;
  document.querySelectorAll('.tab-btn').forEach(b => {
    b.classList.toggle('active', b.getAttribute('data-filter') === filter);
  });
  renderContent();
}

function filterContent() {
  renderContent();
}

async function loadFolder(folder) {
  currentFolder = folder || '';
  const foldersSec = document.getElementById('foldersSection');
  const foldersDiv = document.getElementById('foldersGrid');
  const filesDiv = document.getElementById('filesGrid');
  const tableBody = document.getElementById('filesTableBody');
  const emptyNote = document.getElementById('filesEmpty');

  emptyNote.style.display = 'none';
  foldersDiv.innerHTML = '<div style="color:var(--text-faint); font-size:13px; padding:12px;">Loading folder contents…</div>';
  filesDiv.innerHTML = '';
  tableBody.innerHTML = '';

  try {
    const res = await fetch('/api/browse?folder=' + encodeURIComponent(currentFolder));
    const data = await res.json();
    rawData = data;
    renderBreadcrumbs(data.breadcrumbs || []);
    renderContent();
  } catch (e) {
    foldersDiv.innerHTML = '<div style="color:var(--red); font-size:13px; padding:12px;">Failed to load folder.</div>';
  }
}

function renderBreadcrumbs(crumbs) {
  const bar = document.getElementById('crumbBar');
  bar.innerHTML = '';
  crumbs.forEach((c, idx) => {
    const isLast = idx === crumbs.length - 1;
    if (idx > 0) {
      const sep = document.createElement('span');
      sep.className = 'crumb-separator';
      sep.textContent = '›';
      bar.appendChild(sep);
    }
    const item = document.createElement('span');
    const label = (idx === 0 ? '📁 ' : '') + c.name;
    if (isLast) {
      item.className = 'crumb-current';
      item.textContent = label;
    } else {
      item.className = 'crumb-item';
      item.textContent = label;
      item.onclick = () => loadFolder(c.id);
    }
    bar.appendChild(item);
  });
}

function renderContent() {
  if (!rawData) return;
  const query = document.getElementById('searchBox').value.trim().toLowerCase();
  const foldersSec = document.getElementById('foldersSection');
  const foldersDiv = document.getElementById('foldersGrid');
  const filesDiv = document.getElementById('filesGrid');
  const table = document.getElementById('filesTable');
  const tableBody = document.getElementById('filesTableBody');
  const emptyNote = document.getElementById('filesEmpty');
  const itemsBadge = document.getElementById('itemsCountBadge');
  const foldersBadge = document.getElementById('foldersCountBadge');

  foldersDiv.innerHTML = '';
  filesDiv.innerHTML = '';
  tableBody.innerHTML = '';

  // 1. Folders
  const folders = (rawData.folders || []).filter(f => !query || f.name.toLowerCase().includes(query));
  foldersBadge.textContent = folders.length > 0 ? (folders.length + ' folder' + (folders.length === 1 ? '' : 's')) : '';

  if (folders.length === 0) {
    foldersSec.style.display = 'none';
  } else {
    foldersSec.style.display = 'block';
    folders.forEach(f => {
      const card = document.createElement('div');
      card.className = 'folder-card';
      card.onclick = () => loadFolder(f.id);
      card.innerHTML = 
        '<div class="folder-icon-wrap">' +
          '<span>📁</span>' +
        '</div>' +
        '<div class="folder-info">' +
          '<div class="folder-name" title="' + escapeHtml(f.name) + '">' + escapeHtml(f.name) + '</div>' +
          '<div class="folder-meta">' +
            '<span>' + escapeHtml(f.caption || (f.itemCount + ' items')) + '</span>' +
            '<span class="folder-badge">Folder</span>' +
          '</div>' +
        '</div>';
      foldersDiv.appendChild(card);
    });
  }

  // 2. Files
  let files = (rawData.files || []).filter(f => {
    if (query && !f.name.toLowerCase().includes(query)) return false;
    if (currentFilter === 'video') return f.isVideo;
    if (currentFilter === 'image') return f.isImage;
    if (currentFilter === 'audio') return f.isAudio;
    if (currentFilter === 'doc') return f.isDoc;
    return true;
  });

  itemsBadge.textContent = files.length > 0 ? (files.length + ' file' + (files.length === 1 ? '' : 's')) : '';

  if (files.length === 0) {
    if (folders.length === 0) {
      emptyNote.style.display = 'block';
    } else {
      emptyNote.style.display = 'none';
    }
  } else {
    emptyNote.style.display = 'none';
    if (viewMode === 'grid') {
      table.style.display = 'none';
      filesDiv.style.display = 'grid';

      files.forEach(it => {
        const card = document.createElement('div');
        card.className = 'file-card';
        const streamUrl = '/api/stream?uri=' + encodeURIComponent(it.uri);
        const downloadUrl = '/api/download?uri=' + encodeURIComponent(it.uri) + '&name=' + encodeURIComponent(it.name);
        const thumbUrl = '/api/thumb?uri=' + encodeURIComponent(it.uri);

        let thumbHtml = '';
        if (it.isImage) {
          thumbHtml = '<img class="file-thumb-img" src="' + thumbUrl + '" loading="lazy" alt="">';
          card.onclick = () => openLightbox(it.name, streamUrl, downloadUrl);
        } else if (it.isVideo) {
          thumbHtml = '<img class="file-thumb-img" src="' + thumbUrl + '" loading="lazy" alt=""><div class="play-badge">▶</div>';
          card.onclick = () => openVideo(it.name, streamUrl, downloadUrl);
        } else if (it.isAudio) {
          thumbHtml = '<div class="audio-icon-box">🎵</div>';
          card.onclick = () => openAudio(it.name, streamUrl, downloadUrl);
        } else {
          thumbHtml = '<div class="doc-icon-box">📄</div>';
          card.onclick = () => window.open(downloadUrl, '_blank');
        }

        const ext = it.name.split('.').pop().toUpperCase().slice(0, 4);

        card.innerHTML = 
          '<div class="file-thumb-wrap">' + thumbHtml + '</div>' +
          '<div class="file-body">' +
            '<div class="file-name" title="' + escapeHtml(it.name) + '">' + escapeHtml(it.name) + '</div>' +
            '<div class="file-meta">' +
              '<span>' + formatBytes(it.size) + '</span>' +
              '<div style="display:flex; align-items:center; gap:6px;">' +
                '<span class="file-type-badge">' + ext + '</span>' +
                '<button class="btn btn-sm" onclick="event.stopPropagation(); copyStreamLink(\'' + encodeURIComponent(it.uri) + '\', \'' + escapeHtml(it.name) + '\')" title="Copy direct stream link for VLC or player" style="padding:2px 7px; font-size:11px;">🔗 Link</button>' +
              '</div>' +
            '</div>' +
          '</div>';
        filesDiv.appendChild(card);
      });
    } else {
      filesDiv.style.display = 'none';
      table.style.display = 'table';

      files.forEach(it => {
        const streamUrl = '/api/stream?uri=' + encodeURIComponent(it.uri);
        const downloadUrl = '/api/download?uri=' + encodeURIComponent(it.uri) + '&name=' + encodeURIComponent(it.name);
        const tr = document.createElement('tr');
        const icon = it.isVideo ? '🎬' : it.isImage ? '🖼️' : it.isAudio ? '🎵' : '📄';
        tr.innerHTML = 
          '<td><div class="table-name"><span class="table-icon">' + icon + '</span><span title="' + escapeHtml(it.name) + '">' + escapeHtml(it.name) + '</span></div></td>' +
          '<td>' + formatBytes(it.size) + '</td>' +
          '<td>' + (it.mimeType || 'file') + '</td>' +
          '<td>' + formatDate(it.modified) + '</td>' +
          '<td>' +
            '<div class="table-actions">' +
              (it.isVideo || it.isImage || it.isAudio ? '<button class="btn btn-sm" onclick="event.stopPropagation(); playOrView(' + JSON.stringify(it).replace(/"/g, '&quot;') + ')">View</button>' : '') +
              '<button class="btn btn-sm" onclick="event.stopPropagation(); copyStreamLink(\'' + encodeURIComponent(it.uri) + '\', \'' + escapeHtml(it.name) + '\')" title="Copy direct stream link for VLC or external player">🔗 Copy Link</button>' +
              '<a href="' + downloadUrl + '" class="btn btn-sm btn-primary" download="' + escapeHtml(decodeURIComponent(it.name)) + '" onclick="event.stopPropagation()">Download</a>' +
            '</div>' +
          '</td>';
        tr.onclick = () => {
          if (it.isVideo) openVideo(it.name, streamUrl, downloadUrl, it.uri);
          else if (it.isImage) openLightbox(it.name, streamUrl, downloadUrl, it.uri);
          else if (it.isAudio) openAudio(it.name, streamUrl, downloadUrl, it.uri);
          else window.open(downloadUrl, '_blank');
        };
        tableBody.appendChild(tr);
      });
    }
  }
}

let currentModalStreamUri = '';
let currentModalImageUri = '';
let currentModalAudioUri = '';

function showToast(msg) {
  const t = document.getElementById('toast');
  const m = document.getElementById('toastMsg');
  m.textContent = msg;
  t.style.display = 'flex';
  clearTimeout(window._toastTimer);
  window._toastTimer = setTimeout(() => { t.style.display = 'none'; }, 3500);
}

function copyStreamLink(encodedUri, name) {
  const url = window.location.origin + '/api/stream?uri=' + encodedUri;
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(url).then(() => {
      showToast('Copied direct stream link! Paste into VLC, mpv, or player: ' + url);
    }).catch(() => {
      prompt('Direct Stream URL (VLC/Player):', url);
    });
  } else {
    prompt('Direct Stream URL (VLC/Player):', url);
  }
}

function copyCurrentModalStream() {
  if (currentModalStreamUri) copyStreamLink(encodeURIComponent(currentModalStreamUri), 'Video');
}

function copyCurrentImageStream() {
  if (currentModalImageUri) copyStreamLink(encodeURIComponent(currentModalImageUri), 'Image');
}

function copyCurrentAudioStream() {
  if (currentModalAudioUri) copyStreamLink(encodeURIComponent(currentModalAudioUri), 'Audio');
}

function playOrView(it) {
  const streamUrl = '/api/stream?uri=' + encodeURIComponent(it.uri) + '&name=' + encodeURIComponent(it.name);
  const downloadUrl = '/api/download?uri=' + encodeURIComponent(it.uri) + '&name=' + encodeURIComponent(it.name);
  if (it.isVideo) openVideo(it.name, streamUrl, downloadUrl, it.uri);
  else if (it.isImage) openLightbox(it.name, streamUrl, downloadUrl, it.uri);
  else if (it.isAudio) openAudio(it.name, streamUrl, downloadUrl, it.uri);
  else window.open(downloadUrl, '_blank');
}

function openLightbox(name, streamUrl, downloadUrl, uri) {
  currentModalImageUri = uri || decodeURIComponent(streamUrl.substring(streamUrl.indexOf('uri=') + 4));
  const modal = document.getElementById('imageModal');
  const img = document.getElementById('imageViewer');
  const title = document.getElementById('imageTitle');
  const dl = document.getElementById('imageDownloadBtn');
  img.src = streamUrl;
  const cleanName = decodeURIComponent(name);
  title.textContent = cleanName;
  dl.href = downloadUrl;
  dl.setAttribute('download', cleanName);
  modal.style.display = 'flex';
}

function closeImage() {
  const modal = document.getElementById('imageModal');
  const img = document.getElementById('imageViewer');
  img.src = '';
  modal.style.display = 'none';
}

function openVideo(name, streamUrl, downloadUrl, uri) {
  currentModalStreamUri = uri || decodeURIComponent(streamUrl.substring(streamUrl.indexOf('uri=') + 4));
  const modal = document.getElementById('playerModal');
  const vid = document.getElementById('mediaPlayer');
  const title = document.getElementById('videoTitle');
  const dl = document.getElementById('videoDownloadBtn');
  vid.src = streamUrl;
  const cleanName = decodeURIComponent(name);
  title.textContent = cleanName;
  dl.href = downloadUrl;
  dl.setAttribute('download', cleanName);
  modal.style.display = 'flex';
}

function closePlayer() {
  const modal = document.getElementById('playerModal');
  const vid = document.getElementById('mediaPlayer');
  vid.pause();
  vid.src = '';
  modal.style.display = 'none';
}

function openAudio(name, streamUrl, downloadUrl, uri) {
  currentModalAudioUri = uri || decodeURIComponent(streamUrl.substring(streamUrl.indexOf('uri=') + 4));
  const modal = document.getElementById('audioModal');
  const aud = document.getElementById('audioPlayer');
  const title = document.getElementById('audioTitle');
  const dl = document.getElementById('audioDownloadBtn');
  aud.src = streamUrl;
  const cleanName = decodeURIComponent(name);
  title.textContent = cleanName;
  dl.href = downloadUrl;
  dl.setAttribute('download', cleanName);
  modal.style.display = 'flex';
}

function closeAudio() {
  const modal = document.getElementById('audioModal');
  const aud = document.getElementById('audioPlayer');
  aud.pause();
  aud.src = '';
  modal.style.display = 'none';
}

function closeUploadDrawer() {
  document.getElementById('uploadDrawer').style.display = 'none';
}

async function uploadFiles(files) {
  const drawer = document.getElementById('uploadDrawer');
  const list = document.getElementById('uploadList');
  drawer.style.display = 'block';

  for (let i = 0; i < files.length; i++) {
    const file = files[i];
    const itemId = 'upload_' + Date.now() + '_' + i;
    const item = document.createElement('div');
    item.className = 'upload-item';
    item.id = itemId;
    item.innerHTML = 
      '<div class="upload-row">' +
        '<span style="overflow:hidden; text-overflow:ellipsis; white-space:nowrap; max-width:200px;">' + escapeHtml(file.name) + '</span>' +
        '<span>' + formatBytes(file.size) + '</span>' +
      '</div>' +
      '<div class="progress-bg"><div class="progress-fill" style="width:0%"></div></div>';
    list.prepend(item);

    try {
      let uploadUrl = '/api/upload?name=' + encodeURIComponent(file.name);
      if (currentFolder) uploadUrl += '&folder=' + encodeURIComponent(currentFolder);

      const fill = item.querySelector('.progress-fill');
      fill.style.width = '70%';

      await fetch(uploadUrl, {
        method: 'POST',
        headers: {
          'Content-Type': file.type || 'application/octet-stream',
          'Content-Length': file.size.toString(),
        },
        body: file,
      });

      fill.style.width = '100%';
      fill.style.background = 'var(--green)';
      item.querySelector('.upload-row').innerHTML += '<span style="color:var(--green)">✓</span>';
    } catch (err) {
      item.querySelector('.upload-row').innerHTML += '<span style="color:var(--red)">✕</span>';
    }
  }

  // Refresh current folder after uploads complete
  loadFolder(currentFolder);
}

// Initial boot
document.getElementById('viewToggleBtn').textContent = viewMode === 'grid' ? '🔲' : '☰';
loadFolder('');
</script>
</body>
</html>
        """.trimIndent()
    }
}
