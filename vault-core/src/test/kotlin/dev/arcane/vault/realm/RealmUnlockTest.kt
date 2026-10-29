package dev.arcane.vault.realm

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.arcane.vault.crypto.KdfCalibrator
import dev.arcane.vault.crypto.KdfParams
import dev.arcane.vault.crypto.KeyDerivation
import dev.arcane.vault.crypto.RealmDefaults
import dev.arcane.vault.crypto.Secrets
import dev.arcane.vault.format.VaultFormat
import dev.arcane.vault.pseudoRandom
import dev.arcane.vault.store.FileBlobStore
import dev.arcane.vault.store.VaultLayout
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** 32 MiB at one iteration is the calibrated floor; it keeps the suite honest and fast. */
private fun params(tag: String): KdfParams =
    KdfParams(pseudoRandom(16, tag.hashCode()), RealmDefaults.MEMORY_KIB / 2, 1, 1)

private val SECRET_WORDS = listOf("decoy", "secret", "vault", "hidden", "arcane", "real")

class RealmUnlockTest {

    @TempDir
    lateinit var root: File

    private val layout: VaultLayout by lazy { VaultLayout(root) }
    private val blobs: FileBlobStore by lazy { FileBlobStore(layout) }
    private val headers: RealmHeaderStore by lazy { RealmHeaderStore(layout) }
    private val opener: RealmOpener by lazy { RealmOpener(blobs, headers) }

    @Test
    fun `a header exists at full size before any realm is created`() {
        val header = headers.initialiseIfAbsent()

        assertWithMessage("the header never grows when a realm appears")
            .that(header.size)
            .isEqualTo(RealmSlot.HEADER_BYTES)
        assertThat(blobs.blobIds()).isEmpty()
        assertWithMessage("filler slots cannot unlock because no anchor blob exists yet")
            .that(opener.unlock("anything".toByteArray()))
            .isNull()
    }

    @Test
    fun `two realms share one pool and the header names neither of them`() {
        val real = opener.initialise(slotIndex = 0, "the-real-passphrase".toByteArray(), params("real"))
        val decoy = opener.initialise(slotIndex = 1, "the-decoy-passphrase".toByteArray(), params("decoy"))

        val header = headers.read()
        assertWithMessage("header bytes: ${header.size} long, no realm words anywhere")
            .that(SECRET_WORDS.any { header.toString(Charsets.ISO_8859_1).contains(it) })
            .isFalse()
        val fileNames = root.walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertWithMessage("every name on disk is either the header or a random hex blob: $fileNames")
            .that(fileNames.all { it == "kdf" || Regex("^[0-9a-f]{32}\\.bin$").matches(it) })
            .isTrue()

        val openedReal = opener.unlock("the-real-passphrase".toByteArray())!!
        val openedDecoy = opener.unlock("the-decoy-passphrase".toByteArray())!!
        assertThat(openedReal.slotIndex).isEqualTo(0)
        assertThat(openedDecoy.slotIndex).isEqualTo(1)
        assertThat(openedReal.realmId.toList()).isEqualTo(real.realmId.toList())
        assertThat(openedDecoy.realmId.toList()).isEqualTo(decoy.realmId.toList())
        assertWithMessage("different keys, so the two realms cannot read each other's blobs")
            .that(openedReal.masterKey.toList())
            .isNotEqualTo(openedDecoy.masterKey.toList())
        assertWithMessage("one anchor plus one index chunk per realm, in a single shared pool")
            .that(blobs.blobIds())
            .hasSize(4)
    }

    @Test
    fun `the wrong passphrase opens nothing and says nothing about why`() {
        opener.initialise(0, "correct".toByteArray(), params("one"))
        opener.initialise(1, "decoy".toByteArray(), params("two"))

        assertWithMessage("a wrong passphrase looks exactly like no realm at all")
            .that(opener.unlock("guess".toByteArray()))
            .isNull()
        assertThat(opener.unlock(ByteArray(0))).isNull()
        assertThrows<RealmAlreadyExists> { opener.initialise(0, "correct".toByteArray(), params("one")) }
    }

    @Test
    fun `a header slot with no committed index cannot be unlocked`() {
        // The slot is written but the anchor never is, e.g. a crash between the two.
        val p = params("half")
        headers.writeSlot(0, RealmSlot(VaultFormat.FORMAT_VERSION, p.salt, p.memoryKiB, p.iterations, p.parallelism))

        assertThat(opener.unlock("typed-at-setup".toByteArray())).isNull()
        assertThrows<RealmNotInitialised> { opener.unlockWithMasterKey(0, Secrets.randomBytes(32), p.salt) }
    }

    @Test
    fun `random header filler never decodes into a dangerous allocation`() {
        var decoded = 0
        repeat(20_000) {
            val slot = RealmSlot.decode(Secrets.randomBytes(RealmSlot.SLOT_BYTES))
            if (slot != null) {
                decoded++
                assertWithMessage("a slot decoded from random filler must stay inside the envelope")
                    .that(slot.memoryKiB in RealmSlot.MIN_MEMORY_KIB..RealmSlot.MAX_MEMORY_KIB)
                    .isTrue()
                assertThat(slot.iterations).isIn(1..RealmSlot.MAX_ITERATIONS)
                assertThat(slot.parallelism).isIn(1..KdfParams.MAX_PARALLELISM)
            }
        }
        assertWithMessage("filler almost never decodes, which is the point ($decoded of 20000)")
            .that(decoded)
            .isAtMost(20)
    }

    @Test
    fun `failover opens plain browsing on the third strike with no error surfaced`() {
        val policy = FailoverPolicy(maxWrongAttempts = 3)

        assertThat(policy.onWrongAttempt(0)).isEqualTo(FailoverOutcome.RejectAttempt(1))
        assertThat(policy.onWrongAttempt(1)).isEqualTo(FailoverOutcome.RejectAttempt(2))
        assertWithMessage("the third consecutive failure is the one that stops showing an error")
            .that(policy.onWrongAttempt(2))
            .isEqualTo(FailoverOutcome.FailoverToDecoy(resetTo = 2))
        assertThat(policy.onRealmOpened()).isEqualTo(FailoverOutcome.Opened(resetTo = 0))
    }

    @Test
    fun `a wrong passphrase after a failover keeps the illusion instead of being refused`() {
        val policy = FailoverPolicy(maxWrongAttempts = 4)
        val first = policy.onWrongAttempt(3) as FailoverOutcome.FailoverToDecoy

        assertWithMessage("the next strike must not answer 'not recognised' on a phone that just opened")
            .that(policy.onWrongAttempt(first.resetTo))
            .isInstanceOf(FailoverOutcome.FailoverToDecoy::class.java)
    }

    @Test
    fun `the strike count is drawn per realm and never leaves two to five`() {
        val salts = (0 until 500).map { pseudoRandom(16, it) }
        val counts = salts.map { FailoverPolicy.forSalt(it).maxWrongAttempts }

        assertWithMessage("an out-of-range count would either advertise the failover or hide it too well")
            .that(counts.filter { it !in 2..5 })
            .isEmpty()
        assertWithMessage("every value in the range shows up, so the count is not one number wearing a costume")
            .that(counts.distinct().sorted())
            .containsExactly(2, 3, 4, 5)
            .inOrder()
        assertWithMessage("the same salt always means the same count — a phone must not change its mind mid-argument")
            .that(salts.map { FailoverPolicy.forSalt(it).maxWrongAttempts })
            .isEqualTo(counts)
        assertThat(FailoverPolicy.forSalt(null).maxWrongAttempts).isEqualTo(FailoverPolicy.DEFAULT)
        assertThat(FailoverPolicy.forSalt(ByteArray(0)).maxWrongAttempts).isEqualTo(FailoverPolicy.DEFAULT)
    }

    @Test
    fun `a cached master key reopens a realm with no passphrase typed`() {
        val created = opener.initialise(0, "typed-at-setup".toByteArray(), params("cached"))
        val cached = created.masterKey.copyOf()

        val reopened = opener.unlockWithMasterKey(0, cached, params("cached").salt)
        assertThat(reopened.index.folders).isEmpty()
        assertWithMessage("the reopened realm reads the same anchor as the original")
            .that(reopened.generation)
            .isEqualTo(created.generation)
    }
}

class KdfCalibratorTest {

    @Test
    fun `iterations climb until the target cost is reached`() {
        val costPerIteration = 90L
        val calibrator = KdfCalibrator(
            targetMillis = 500,
            maxMillis = 1_500,
            memoryKiB = 32 * 1024,
            measure = { params -> costPerIteration * params.iterations },
        )

        val chosen = calibrator.calibrate()

        assertWithMessage("90 ms per iteration reaches 500 ms at six")
            .that(chosen.iterations)
            .isEqualTo(6)
        assertThat(chosen.memoryKiB).isEqualTo(32 * 1024)
        assertThat(chosen.parallelism).isEqualTo(1)
        assertThat(chosen.salt.size).isEqualTo(KdfParams.MIN_SALT_BYTES)
    }

    @Test
    fun `a slow device gives iterations back rather than a two second unlock`() {
        val chosen = KdfCalibrator(
            targetMillis = 500,
            maxMillis = 900,
            memoryKiB = 32 * 1024,
            measure = { params -> 400L * params.iterations },
        ).calibrate()

        assertThat(chosen.iterations).isEqualTo(2)
    }

    @Test
    fun `an extremely fast device raises memory instead of skipping the cost floor`() {
        // 1 ms per iteration: even 16 iterations cannot reach the 200 ms floor at 32 MiB, so memory climbs.
        val chosen = KdfCalibrator(
            targetMillis = 500,
            maxMillis = 1_500,
            minAcceptableMillis = 200,
            memoryKiB = 32 * 1024,
            measure = { params -> (params.memoryKiB / 16_384L) * 40 + params.iterations },
        ).calibrate()

        assertWithMessage("memory doubled until the 200 ms floor was reachable")
            .that(chosen.memoryKiB)
            .isEqualTo(128 * 1024)
        assertThat(chosen.iterations).isEqualTo(RealmDefaults.MAX_ITERATIONS)
    }

    @Test
    fun `the default cost model times actual argon2 work`() {
        // A target of zero means "stay at one iteration", so exactly one run is timed and nothing escalates.
        val chosen = KdfCalibrator(targetMillis = 0, maxMillis = 1, minAcceptableMillis = 0, memoryKiB = 32 * 1024)
            .calibrate()

        assertThat(chosen.iterations).isEqualTo(1)
        assertThat(KeyDerivation.argon2id("pass".toByteArray(), chosen)).hasLength(32)
    }
}
