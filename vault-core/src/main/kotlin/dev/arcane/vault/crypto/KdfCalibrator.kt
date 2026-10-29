package dev.arcane.vault.crypto

/**
 * Picks the Argon2id cost that makes unlock take about [targetMillis] on *this* device, and records it in
 * the realm header so later unlocks use the same numbers.
 *
 * Iterations move first because raising memory past the point where it stops fitting the cache does not
 * slow an attacker proportionally, but it does risk an OOM kill on a low-RAM phone. Memory only climbs
 * when even the iteration ceiling cannot reach [minAcceptableMillis], because a cost floor that can be
 * skipped is not a floor.
 */
class KdfCalibrator(
    private val targetMillis: Long = 500,
    private val maxMillis: Long = 1_500,
    private val minAcceptableMillis: Long = 200,
    private val memoryKiB: Int = RealmDefaults.MEMORY_KIB,
    private val parallelism: Int = 1,
    private val measure: (KdfParams) -> Long = ::measureArgon2Millis,
) {

    /**
     * The passphrase is not used for timing: Argon2id is memory-bound, so an empty buffer measures the
     * same cost and keeps the real passphrase out of a loop that runs two dozen times.
     */
    fun calibrate(): KdfParams {
        val salt = Secrets.randomBytes(KdfParams.MIN_SALT_BYTES)
        var memory = memoryKiB
        while (true) {
            val attempt = search(salt, memory)
            if (attempt.elapsedMillis >= minAcceptableMillis || memory >= RealmDefaults.MAX_MEMORY_KIB) {
                return attempt.params
            }
            memory *= 2
        }
    }

    private fun search(salt: ByteArray, memoryKiB: Int): Calibrated {
        var iterations = 1
        var elapsed = time(salt, memoryKiB, iterations)
        // Doubling is a coarse probe; the useful range on a phone is 1..16 so nothing smarter pays off.
        while (elapsed < targetMillis / 2 && iterations < MAX_PROBE_ITERATIONS) {
            iterations *= 2
            elapsed = time(salt, memoryKiB, iterations)
        }
        while (elapsed < targetMillis && iterations < RealmDefaults.MAX_ITERATIONS) {
            iterations++
            elapsed = time(salt, memoryKiB, iterations)
        }
        while (elapsed > maxMillis && iterations > 1) {
            iterations--
            elapsed = time(salt, memoryKiB, iterations)
        }
        return Calibrated(KdfParams(salt, memoryKiB, iterations, parallelism), elapsed)
    }

    private fun time(salt: ByteArray, memoryKiB: Int, iterations: Int): Long =
        measure(KdfParams(salt, memoryKiB, iterations, parallelism))

    private data class Calibrated(val params: KdfParams, val elapsedMillis: Long)

    private companion object {
        const val MAX_PROBE_ITERATIONS = 8
    }
}

object RealmDefaults {
    /** The plan's stated working memory: 64 MiB, climbing to 1 GiB only if the device is that fast. */
    const val MEMORY_KIB = 64 * 1024
    const val MAX_MEMORY_KIB = 512 * 1024
    const val MAX_ITERATIONS = 16
}

/** Real cost, measured by doing the work. Argon2id output is discarded; only the duration matters. */
private fun measureArgon2Millis(params: KdfParams): Long {
    val start = System.nanoTime()
    KeyDerivation.argon2id(PLACEHOLDER, params)
    return ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
}

private val PLACEHOLDER = ByteArray(0)
