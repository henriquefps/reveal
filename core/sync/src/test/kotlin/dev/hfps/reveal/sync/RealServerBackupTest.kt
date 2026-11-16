package dev.hfps.reveal.sync

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.ImageInput
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.PhotoFileEntity
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.db.SyncUploadEntity
import dev.hfps.reveal.data.db.VersionEntity
import dev.hfps.reveal.data.library.DocumentTreeFactory
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.storage.DocumentEntry
import dev.hfps.reveal.data.storage.DocumentTree
import dev.hfps.reveal.data.sync.BackupCoverage
import dev.hfps.reveal.data.sync.StorageLocation
import dev.hfps.reveal.pipeline.preview.SmartPreviewMaker
import dev.hfps.reveal.sync.account.ClientDevice
import dev.hfps.reveal.sync.account.PairingLink
import dev.hfps.reveal.sync.api.DropResult
import dev.hfps.reveal.sync.api.PairRequest
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.blobs.BlobUploader
import dev.hfps.reveal.sync.engine.ServerLibrary
import dev.hfps.reveal.sync.engine.SyncEngine
import dev.hfps.reveal.sync.engine.SyncStore
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * One backup per phone (Hoard ADR 0024 + its S1 amendment, ADR 0018 "R1") against a real hoard-server 0.12.0+
 * (`backup`). Each test pairs two device keys on one phone, "Reveal" (this app's [TestDevice]) and "Hoard" (a raw HTTP
 * client driving the backup API as Hoard does: roots, reports, uploads with `claim: false`), grouped as one physical
 * device by Hoard's same-phone claim. `REVEAL_BACKUP_DIR` = the server's work dir (the test mints its own pairing
 * links with the script, two per test). Skipped without it; run with `--no-configuration-cache`:
 *
 * ```
 * D=$(mktemp -d) && scripts/sync/local-server.sh start $D 3477
 * REVEAL_REPO=$PWD REVEAL_BACKUP_DIR=$D \
 *   ./gradlew --no-configuration-cache :core:sync:testDebugUnitTest --tests '*RealServerBackupTest*'
 * scripts/sync/local-server.sh stop $D
 * ```
 *
 * Not covered here: a **stalled** Hoard upload (no chunk for 10 minutes) ending Reveal's wait; the server's window is
 * wall-clock and not configurable, so the test covers the two quick ends of the wait instead (Hoard's upload completes,
 * or Hoard abandons it). `FakeSyncServer`'s `PhoneBackupTest` covers the client side of every `in_progress` answer.
 */
@RunWith(AndroidJUnit4::class)
class RealServerBackupTest {
    private val dir = System.getenv("REVEAL_BACKUP_DIR")
    private val model = ClientDevice("reveal", "samsung", "SM-S928B")
    private val opened = ArrayList<AutoCloseable>()
    private lateinit var files: File

    @Before
    fun setUp() {
        assumeTrue("needs REVEAL_BACKUP_DIR", !dir.isNullOrBlank())
        files = Files.createTempDirectory("real-backup").toFile()
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        if (::files.isInitialized) files.deleteRecursively()
    }

    // ------------------------------------------------------------------ one phone: Reveal and Hoard

    /** Reveal and Hoard paired on one phone and grouped into Reveal's physical device. */
    private inner class Phone(val reveal: TestDevice, val hoard: Hoard, val physicalDeviceId: String) {
        val uploader = BlobUploader(
            reveal.db,
            reveal.store,
            DocumentTreeFactory { FileTree(files) },
            SmartPreviewMaker(File(files, "made")),
        ) { reveal.now }

        fun originals(): Int = runBlocking { uploader.originals(reveal.api, 50) }

        fun sync() {
            reveal.sync()
        }
    }

    private fun link(): PairingLink {
        val script = File(System.getenv("REVEAL_REPO") ?: "../..", "scripts/sync/local-server.sh")
        val process = ProcessBuilder("bash", script.path, "pair", dir!!).redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().decodeToString().trim()
        check(process.waitFor() == 0) { "pair failed: $out" }
        return checkNotNull(PairingLink.parse(out.lines().last()))
    }

    /** A phone of a model of its own (so earlier tests' phones are never this one's "same phone?" candidates). */
    private fun phone(): Phone {
        val name = "Phone ${UUID.randomUUID().toString().take(8)}"
        val device = model.copy(model = "SM-${UUID.randomUUID().toString().take(6)}")
        val revealLink = link()
        val paired = SyncApi(revealLink.url, null).pair(
            PairRequest(revealLink.token, name, "android", "test", "reveal", device.manufacturer, device.model),
        )
        val reveal = TestDevice(
            name,
            revealLink.url,
            paired.apiKey,
            paired.serverId,
            now = System.currentTimeMillis(),
            device = device,
        ).also { opened += it }
        val hoardLink = link()
        val hoardPaired = SyncApi(hoardLink.url, null).pair(
            PairRequest(hoardLink.token, name, "android", "test", "hoard", device.manufacturer, device.model),
        )
        // Hoard's "same phone?": the Reveal of this model is its candidate; claiming it groups the two.
        val physical = checkNotNull(paired.physicalDeviceId)
        assertThat(hoardPaired.candidates.map { it.physicalDeviceId }).contains(physical)
        val hoard = Hoard(hoardLink.url, hoardPaired.apiKey)
        assertThat(hoard.api.claim(physical).physicalDevice!!.id).isEqualTo(physical)
        return Phone(reveal, hoard, physical)
    }

    /** An added folder of Android's own storage provider ([rootDocumentId] = `primary:DCIM/Camera`). */
    private fun externalFolder(phone: Phone, rootDocumentId: String, backup: Boolean = false): Long = runBlocking {
        phone.reveal.db.sourceDao().insert(
            SourceEntity(
                treeUri = "content://${BackupCoverage.EXTERNAL_STORAGE_AUTHORITY}/tree/" +
                    rootDocumentId.replace(":", "%3A").replace("/", "%2F"),
                rootDocumentId = rootDocumentId,
                displayName = rootDocumentId.substringAfterLast('/'),
                volumeId = rootDocumentId.substringBefore(':'),
                volumeName = null,
                state = SourceState.AVAILABLE,
                writable = false,
                addedAt = 0,
                backup = backup,
            ),
        )
    }

    /**
     * A one-file photo of [source] as the indexer adds it, its bytes readable by the uploader (`<files>/<name>`);
     * [printed] false = Reveal has not fingerprinted it yet.
     */
    private fun photo(
        phone: Phone,
        source: Long,
        name: String,
        bytes: ByteArray,
        modified: Long = MTIME,
        printed: Boolean = true,
    ): Long = runBlocking {
        val db = phone.reveal.db
        val raw = dev.hfps.image.format.ImageFormats.classify(name).isRaw
        val id = db.photoDao().insertPhoto(
            PhotoEntity(
                sourceId = source, folder = "", folderDocumentId = db.sourceDao().get(source)!!.rootDocumentId,
                stemKey = name.substringBeforeLast('.').lowercase(), name = name,
                format = SyncStore.formatOf(listOf(name)), hasRaw = raw, sortTime = modified,
                addedAt = phone.reveal.now,
            ),
        )
        db.photoDao().insertFiles(
            listOf(
                PhotoFileEntity(
                    photoId = id, sourceId = source, relativePath = name, documentId = name, name = name,
                    size = bytes.size.toLong(), modified = modified,
                    role = if (raw) FileRole.RAW else FileRole.RENDERED,
                    fingerprint = FileFingerprint.of(bytes).takeIf { printed },
                ),
            ),
        )
        db.photoDao().insertVersion(VersionEntity(photoId = id, createdAt = phone.reveal.now))
        File(files, name).writeBytes(bytes)
        id
    }

    private fun unique(prefix: String, ext: String) = "${prefix}_${System.nanoTime()}.$ext"

    private fun bytes(size: Int = 4096) = Random(UUID.randomUUID().hashCode()).nextBytes(size)

    private fun sha(bytes: ByteArray) = BlobUploader.sha256(ByteArrayInputStream(bytes))

    private fun entry(path: String, bytes: ByteArray, modified: Long = MTIME, withSha: Boolean = true) = Entry(
        path,
        FileFingerprint.of(bytes),
        bytes.size.toLong(),
        modified,
        sha(bytes).takeIf { withSha },
    )

    private fun cloud(phone: Phone, photoId: Long) = runBlocking { phone.reveal.db.syncDao().cloud(photoId) }

    private fun originalUploads(phone: Phone) =
        runBlocking { phone.reveal.db.syncDao().pendingUploads(SyncUploadEntity.KIND_ORIGINAL, 100) }

    private fun serverHas(phone: Phone, bytes: ByteArray): Boolean =
        phone.reveal.api.lookup(listOf(FileFingerprint.of(bytes))).found.isNotEmpty()

    /** Polls the root's mirror locations until [done] holds them (the server's items follow a report shortly). */
    private fun awaitItems(
        phone: Phone,
        root: String,
        what: String,
        done: (List<dev.hfps.reveal.sync.api.ItemLocationRecord>) -> Boolean,
    ) {
        val until = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < until) {
            val items = phone.reveal.api.items(root, null, null, 500).items
            if (done(items.flatMap { it.files }.flatMap { it.locations }.filter { it.source == "mirror" })) return
            Thread.sleep(POLL_MS)
        }
        error("never: $what")
    }

    /** Polls until the server's grid previews of [items] are ready (Hoard ADR 0015: the server makes them). */
    private fun awaitPreviews(phone: Phone, items: List<String>) {
        val until = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < until) {
            val warm = phone.reveal.api.warmPreviews(items)
            if (warm.items.size == items.size && warm.items.all { it.thumb?.status == "ready" }) return
            check(warm.items.none { it.thumb?.status == "unavailable" }) { "a preview is unavailable: $warm" }
            Thread.sleep(POLL_MS)
        }
        error("never: previews of $items")
    }

    private fun awaitItem(phone: Phone, fingerprint: String): String {
        val until = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < until) {
            phone.reveal.api.itemsLookup(listOf(fingerprint)).items.firstOrNull()?.let { return it.id }
            Thread.sleep(POLL_MS)
        }
        error("never: an item for $fingerprint")
    }

    /** Distinct pixels each time (every still is new content for the server). */
    private fun pixels(width: Int, height: Int): IntArray {
        val random = Random(UUID.randomUUID().hashCode())
        return IntArray(width * height) { 0xFF000000.toInt() or random.nextInt(0x1000000) }
    }

    private fun png(): ByteArray {
        val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, 64, 48, pixels(64, 48), 0, 64)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun gif(): ByteArray {
        val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, 64, 48, pixels(64, 48), 0, 64)
        return ByteArrayOutputStream().also { check(ImageIO.write(image, "gif", it)) }.toByteArray()
    }

    /** Through Android's encoder (Robolectric's native graphics): the JDK writes no WebP. */
    private fun webp(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(
            pixels(64, 48),
            64,
            48,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        return ByteArrayOutputStream().also {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it))
        }.toByteArray()
    }

    // ------------------------------------------------------------------ the tests

    @Test
    fun theRealDeviceViewsRootsCoverFoldersByVolumePathAndTypes() {
        val phone = phone()
        val camera = phone.hoard.addRoot("primary", "DCIM/Camera", listOf("image"))
        val card = phone.hoard.addRoot("1a2b-3c4d", "DCIM", listOf("image", "raw"))
        // S1: the primary volume's "SD card <UUID>" first segment is the tree's, refused (422).
        assertThat(phone.hoard.addRootStatus("primary", "SD card 1A2B-3C4D/DCIM")).isEqualTo(422)
        // Another phone's root is never this one's.
        phone().hoard.addRoot("primary", "Pictures", listOf("image"))
        phone.sync()

        val roots = BackupCoverage.decode(runBlocking { phone.reveal.db.metaDao().get(BackupCoverage.META_ROOTS) })
        assertThat(roots.map { it.id }).containsExactly(camera, card)
        val cameraRoot = roots.single { it.id == camera }
        assertThat(cameraRoot.volume).isEqualTo("primary")
        assertThat(cameraRoot.path).isEqualTo("DCIM/Camera")
        assertThat(cameraRoot.types).containsExactly("image")
        assertThat(cameraRoot.active).isTrue()
        assertThat(cameraRoot.app).isEqualTo("hoard")
        // Volumes come back upper-case; Reveal's SAF ids compare without case.
        assertThat(roots.single { it.id == card }.volume).isEqualTo("1A2B-3C4D")

        val inCamera = StorageLocation("primary", "DCIM/Camera/Trip")
        assertThat(BackupCoverage.coveringRoot(roots, inCamera, "PXL_1.jpg")?.id).isEqualTo(camera)
        // Not of the root's types: a CR3 is `raw` only, never covered by an `image` root.
        assertThat(BackupCoverage.coveringRoot(roots, inCamera, "IMG_2.CR3")).isNull()
        assertThat(BackupCoverage.coveringRoot(roots, StorageLocation("primary", "DCIM"), "PXL_3.jpg")).isNull()
        assertThat(BackupCoverage.coveringRoot(roots, StorageLocation("primary", "Pictures"), "a.jpg")).isNull()
        val onCard = StorageLocation("1a2b-3c4d", "DCIM/100CANON")
        assertThat(BackupCoverage.coveringRoot(roots, onCard, "IMG_4.CR3")?.id).isEqualTo(card)

        // Paused: no longer active, so no longer covering; resumed: covering again.
        phone.hoard.pause(card, true)
        phone.sync()
        val paused = BackupCoverage.decode(runBlocking { phone.reveal.db.metaDao().get(BackupCoverage.META_ROOTS) })
        assertThat(paused.single { it.id == card }.active).isFalse()
        assertThat(BackupCoverage.coveringRoot(paused, onCard, "IMG_4.CR3")).isNull()
        // A type added later (Hoard got All files access): covered from the next sync.
        phone.hoard.pause(card, false)
        phone.hoard.setTypes(camera, listOf("image", "raw"))
        phone.sync()
        val later = BackupCoverage.decode(runBlocking { phone.reveal.db.metaDao().get(BackupCoverage.META_ROOTS) })
        assertThat(BackupCoverage.coveringRoot(later, inCamera, "IMG_2.CR3")?.id).isEqualTo(camera)
        assertThat(BackupCoverage.coveringRoot(later, onCard, "IMG_4.CR3")?.id).isEqualTo(card)
    }

    @Test
    @Suppress("LongMethod")
    fun coveredCandidatesWaitADayThenGoWithWaitAndHoardsLiveUploadHoldsThem() {
        val phone = phone()
        val root = phone.hoard.addRoot("primary", "DCIM/Camera", listOf("image"))
        val camera = externalFolder(phone, "primary:DCIM/Camera", backup = true)
        val hoardTakes = bytes()
        val hoardSlow = bytes()
        val hoardDrops = bytes()
        val raw = bytes()
        val takesName = unique("PXL_A", "jpg")
        val slowName = unique("PXL_B", "jpg")
        val dropsName = unique("PXL_C", "jpg")
        val takes = photo(phone, camera, takesName, hoardTakes)
        val slow = photo(phone, camera, slowName, hoardSlow)
        val drops = photo(phone, camera, dropsName, hoardDrops)
        val cr3 = photo(phone, camera, unique("IMG_D", "CR3"), raw)
        phone.sync()

        phone.originals()
        // The CR3 is not of the root's types: it goes at once (with `wait`, harmless: nobody else sends it).
        assertThat(serverHas(phone, raw)).isTrue()
        assertThat(cloud(phone, cr3)!!.originalOnServer).isTrue()
        // The covered JPEGs wait, nothing uploaded.
        val start = phone.reveal.now
        for (id in listOf(takes, slow, drops)) assertThat(cloud(phone, id)!!.candidateAt).isEqualTo(start)
        assertThat(originalUploads(phone)).isEmpty()
        for (b in listOf(hoardTakes, hoardSlow, hoardDrops)) assertThat(serverHas(phone, b)).isFalse()

        // During the day Hoard reports the folder and backs up one file whole; two of its uploads are under way.
        val report = phone.hoard.report(
            root,
            listOf(
                entry("DCIM/Camera/$takesName", hoardTakes),
                entry("DCIM/Camera/$slowName", hoardSlow),
                entry("DCIM/Camera/$dropsName", hoardDrops, withSha = false),
            ),
        )
        assertThat(report.states()).containsExactly("missing", "missing", "missing")
        phone.hoard.upload(hoardTakes)
        val slowUpload = phone.hoard.startUpload(hoardSlow)
        val dropsUpload = phone.hoard.startUpload(hoardDrops)
        phone.reveal.now += 23 * HOUR
        phone.originals()
        assertThat(originalUploads(phone)).isEmpty()

        // A day later: asked again. Hoard's file is on the server (never sent); the two others are queued and sent
        // with `wait`, answered `in_progress` (Hoard is sending them): queued, no failure, asked again after 300 s.
        phone.reveal.now += 2 * HOUR
        phone.originals()
        assertThat(cloud(phone, takes)!!.originalOnServer).isTrue()
        val queued = originalUploads(phone)
        assertThat(queued.map { it.photoId }).containsExactly(slow, drops)
        for (upload in queued) {
            assertThat(upload.state).isEqualTo(SyncUploadEntity.STATE_PENDING)
            assertThat(upload.attempts).isEqualTo(0)
            assertThat(upload.uploadId).isNull()
            assertThat(upload.retryAt).isEqualTo(phone.reveal.now + 300 * SECOND)
        }

        // Hoard finishes one and abandons the other. Before retryAfter nothing is asked; after it, the finished one
        // `exists` (Reveal sends no byte) and the abandoned one is Reveal's to upload.
        phone.hoard.finish(slowUpload, hoardSlow)
        phone.hoard.abandon(dropsUpload)
        phone.reveal.now += 200 * SECOND
        phone.originals()
        assertThat(originalUploads(phone)).hasSize(2)
        phone.reveal.now += 101 * SECOND
        phone.originals()
        assertThat(originalUploads(phone)).isEmpty()
        assertThat(cloud(phone, slow)!!.originalOnServer).isTrue()
        assertThat(cloud(phone, drops)!!.originalOnServer).isTrue()
        assertThat(serverHas(phone, hoardDrops)).isTrue()

        // Hoard's next report finds every file stored, Reveal's upload included (one store, ADR 0024).
        val again = phone.hoard.report(
            root,
            listOf(
                entry("DCIM/Camera/$takesName", hoardTakes),
                entry("DCIM/Camera/$slowName", hoardSlow),
                entry("DCIM/Camera/$dropsName", hoardDrops),
            ),
        )
        assertThat(again.states()).containsExactly("stored", "stored", "stored")
    }

    @Test
    fun hoardIsTheOneWaitingWhileRevealUploads() {
        val phone = phone()
        val root = phone.hoard.addRoot("primary", "DCIM/Camera", listOf("image"))
        val bytes = bytes()
        val name = unique("PXL_E", "jpg")
        // Reveal's own upload under way (uncovered folder elsewhere); Hoard's report and `wait` see it.
        val upload = phone.reveal.api.createUpload(
            dev.hfps.reveal.sync.api.UploadCreateRequest(
                sha(bytes),
                bytes.size.toLong(),
                "original",
                FileFingerprint.of(bytes),
                wait = true,
            ),
        )
        assertThat(upload.status).isEqualTo("created")
        val report = phone.hoard.report(root, listOf(entry("DCIM/Camera/$name", bytes)))
        assertThat(report.states()).containsExactly("in_progress")
        assertThat(report.results().single()["retryAfter"]!!.jsonPrimitive.content).isEqualTo("300")
        assertThat(phone.hoard.create(bytes, wait = true)["status"]!!.jsonPrimitive.content).isEqualTo("in_progress")
    }

    @Test
    fun aBadEntryIsRefusedOnItsOwnAndTheRestOfTheReportApplies() {
        val phone = phone()
        val root = phone.hoard.addRoot("primary", "DCIM/Camera", listOf("image"))
        val good = bytes()
        val notes = bytes()
        val report = phone.hoard.report(
            root,
            listOf(
                entry("DCIM/Camera/${unique("PXL_K", "jpg")}", good),
                entry("DCIM/Camera/${unique("notes", "txt")}", notes),
                entry("Pictures/${unique("PXL_L", "jpg")}", notes),
            ),
        )
        assertThat(report.status).isEqualTo(200)
        assertThat(report.states()).containsExactly("missing", "refused", "refused").inOrder()
        assertThat(report.reasons()).containsExactly(null, "unsupported_type", "outside_root").inOrder()
    }

    /**
     * Screenshots, stickers and GIFs: Hoard 0.12.0 mirrors PNG, WebP and GIF (`image`). Reveal covers them like any
     * still, adopts them as Server photos when Hoard frees them, the server makes their grid previews, and a mirror
     * item of each kind handed over by Hoard (its Gallery on another device) is a Server photo of that format.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Suppress("LongMethod")
    fun pngWebpAndGifFromThePhonesBackupAreServerPhotos() {
        val phone = phone()
        val root = phone.hoard.addRoot("primary", "Pictures/Screenshots", listOf("image"))
        val folder = externalFolder(phone, "primary:Pictures/Screenshots", backup = true)
        val stills = listOf("png" to png(), "webp" to webp(), "gif" to gif())
        val names = stills.map { (ext, _) -> unique("Screenshot", ext) }
        val ids = stills.zip(names).map { (still, name) -> photo(phone, folder, name, still.second) }
        assertThat(ids.map { phone.reveal.photo(it).format })
            .containsExactly(PhotoFormat.PNG, PhotoFormat.WEBP, PhotoFormat.GIF).inOrder()
        phone.sync()

        // Covered (`image` takes the three): they wait for Hoard instead of going at once.
        phone.originals()
        for (id in ids) assertThat(cloud(phone, id)!!.candidateAt).isNotNull()
        assertThat(originalUploads(phone)).isEmpty()

        // Hoard backs them up (each accepted by the report), then frees them.
        val entries = stills.zip(names).map { (still, name) -> entry("Pictures/Screenshots/$name", still.second) }
        assertThat(phone.hoard.report(root, entries).states()).containsExactly("missing", "missing", "missing")
        stills.forEach { phone.hoard.upload(it.second) }
        assertThat(phone.hoard.report(root, entries).states()).containsExactly("stored", "stored", "stored")
        phone.hoard.report(root, emptyList(), entries.map { it.path to "freed" })
        val paths = entries.map { it.path }.toSet()
        awaitItems(phone, root, "the stills freed") { locations ->
            locations.filter { it.path in paths }.let { l -> l.size == paths.size && l.all { it.gone == "freed" } }
        }
        runBlocking {
            val dao = phone.reveal.db.photoDao()
            for (id in ids) dao.markVanished(dao.files(id).map { it.id }, phone.reveal.now)
        }
        assertThat(phone.reveal.sync().freed.adopted).isEqualTo(3)
        val server = runBlocking { checkNotNull(phone.reveal.db.syncDao().serverSource()).id }
        for (id in ids) assertThat(phone.reveal.photo(id).sourceId).isEqualTo(server)
        assertThat(ids.map { phone.reveal.photo(it).format })
            .containsExactly(PhotoFormat.PNG, PhotoFormat.WEBP, PhotoFormat.GIF).inOrder()

        // The grid's thumbnails of Server photos are the server's previews: one for each kind (a GIF's first frame).
        val items = stills.map { (_, b) ->
            checkNotNull(phone.reveal.api.itemsLookup(listOf(FileFingerprint.of(b))).items.singleOrNull()).id
        }
        awaitPreviews(phone, items)

        // Hoard's Gallery hands over a mirror item of each kind (never in Reveal before): Server photos too.
        val more = listOf("png" to png(), "webp" to webp(), "gif" to gif())
        val moreNames = more.map { (ext, _) -> unique("Sticker", ext) }
        val moreEntries = more.zip(moreNames).map { (still, name) -> entry("Pictures/Screenshots/$name", still.second) }
        phone.hoard.report(root, moreEntries)
        more.forEach { phone.hoard.upload(it.second) }
        assertThat(phone.hoard.report(root, moreEntries).states()).containsExactly("stored", "stored", "stored")
        val refs = more.zip(moreNames).map { (still, name) ->
            val fp = FileFingerprint.of(still.second)
            val item = awaitItem(phone, fp)
            dev.hfps.reveal.data.handoff.ServerItemRef(item, fp, name = name)
        }
        val serverId = phone.reveal.api.server().serverId
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val handoffs = dev.hfps.reveal.data.handoff.HandoffRepository(
            context.contentResolver,
            phone.reveal.db,
            dev.hfps.reveal.data.index.LibraryIndexer(phone.reveal.db),
            server = dev.hfps.reveal.sync.handoff.SyncServerItems(
                pairingOf = {
                    dev.hfps.reveal.data.handoff.ServerPairing(serverId, "Home", phone.reveal.api.server().deviceId)
                },
                api = { phone.reveal.api },
                pull = {},
            ),
        )
        val outcome = runBlocking {
            handoffs.receive(
                dev.hfps.reveal.data.handoff.HandoffRequest(
                    uris = emptyList(),
                    title = "Hoard",
                    server = dev.hfps.reveal.data.handoff.ServerRefs(serverId, refs, initialItem = refs.first().itemId),
                ),
            )
        }
        outcome as dev.hfps.reveal.data.handoff.HandoffOutcome.OpenEditor
        assertThat(outcome.notFound).isEqualTo(0)
        val rows = runBlocking {
            phone.reveal.db.photoDao().rows(dev.hfps.reveal.data.db.PhotoQuerySql.toQuery(PhotoQuery.FROM_APPS))
        }
        assertThat(rows.map { it.name }).containsExactlyElementsIn(moreNames)
        assertThat(rows.map { phone.reveal.photo(it.id).format })
            .containsExactly(PhotoFormat.PNG, PhotoFormat.WEBP, PhotoFormat.GIF)
        for (row in rows) {
            assertThat(phone.reveal.photo(row.id).sourceId).isEqualTo(server)
            assertThat(cloud(phone, row.id)!!.originalOnServer).isTrue()
        }
    }

    @Test
    fun deletingAnOriginalHoardMirrorsIs409MirrorAndTheFileStaysOnTheServer() {
        val phone = phone()
        val root = phone.hoard.addRoot("primary", "DCIM/Camera", listOf("image"))
        // Reveal backs up a folder Hoard does not cover; the same picture is also in Hoard's camera folder.
        val pictures = externalFolder(phone, "primary:Pictures/Exports", backup = true)
        val bytes = bytes()
        val name = unique("EXP", "jpg")
        val photo = photo(phone, pictures, name, bytes)
        phone.sync()
        phone.originals()
        assertThat(cloud(phone, photo)!!.originalOnServer).isTrue()
        val report = phone.hoard.report(root, listOf(entry("DCIM/Camera/$name", bytes)))
        assertThat(report.states()).containsExactly("stored")

        // The folder is removed with "delete from server": the records go (pushed), then the delete is asked.
        val removal = runBlocking { ServerLibrary(phone.reveal.db, phone.reveal.store).removeFolder(pictures, true) }
        assertThat(removal.originalsQueued).isEqualTo(1)
        phone.sync()
        val fp = FileFingerprint.of(bytes)
        assertThat(phone.reveal.api.deleteOriginal(fp)).isEqualTo(DropResult.MIRROR)

        // What the blob job does with that answer: done at once, the file still known to be on the server.
        runBlocking {
            phone.reveal.db.syncDao().queueDeletes(
                listOf(dev.hfps.reveal.data.db.SyncDeleteEntity(fp, phone.reveal.now)),
            )
        }
        assertThat(runBlocking { phone.uploader.deleteOriginals(phone.reveal.api) }).isEqualTo(1)
        assertThat(runBlocking { phone.reveal.db.syncDao().pendingDeletes(10) }).isEmpty()
        assertThat(runBlocking { phone.reveal.db.syncDao().knownOnServer(fp) }).isTrue()
        assertThat(runBlocking { phone.reveal.db.metaDao().get(BlobUploader.KEY_MIRROR_KEPT) }).isNotNull()
        assertThat(phone.reveal.api.originalSize(fp)).isEqualTo(bytes.size.toLong())
    }

    @Test
    @Suppress("LongMethod")
    fun vanishedFolderPhotosAreAdoptedWhenFreedOrEditedAndOtherwiseGo() {
        val phone = phone()
        val root = phone.hoard.addRoot("primary", "DCIM/Camera", listOf("image", "raw"))
        val camera = externalFolder(phone, "primary:DCIM/Camera")
        val freedBytes = bytes()
        val unprintedBytes = bytes()
        val editedBytes = bytes()
        val plainBytes = bytes()
        val decoyBytes = bytes()
        val freedName = unique("PXL_F", "jpg")
        val unprintedName = unique("PXL_G", "jpg")
        val editedName = unique("PXL_H", "jpg")
        val plainName = unique("PXL_I", "jpg")
        val decoyName = unique("PXL_J", "jpg")
        val mtime = 1_730_000_000_456L
        // In a sub-directory of the root: the folder query's `path` is relative to the root ("Trip").
        val trip = externalFolder(phone, "primary:DCIM/Camera/Trip")
        val freed = photo(phone, camera, freedName, freedBytes)
        val unprinted = photo(phone, trip, unprintedName, unprintedBytes, modified = mtime, printed = false)
        val edited = photo(phone, camera, editedName, editedBytes)
        val plain = photo(phone, camera, plainName, plainBytes)
        // Same path shape, but Hoard's file has another size: never this one.
        val decoy = photo(phone, trip, decoyName, decoyBytes, modified = mtime, printed = false)
        phone.reveal.edit(phone.reveal.version(freed)) { it.copy(light = it.light.copy(exposureEv = 0.4f)) }
        phone.reveal.edit(phone.reveal.version(edited)) { it.copy(light = it.light.copy(exposureEv = 0.7f)) }
        val uuid = phone.reveal.photo(freed).uuid
        phone.sync()

        // Hoard backs the folder up (Hoard's mtime of the unprinted one is whole seconds: MediaStore)...
        val entries = listOf(
            entry("DCIM/Camera/$freedName", freedBytes),
            entry("DCIM/Camera/Trip/$unprintedName", unprintedBytes, modified = mtime / SECOND * SECOND),
            entry("DCIM/Camera/$editedName", editedBytes),
            entry("DCIM/Camera/$plainName", plainBytes),
            entry("DCIM/Camera/Trip/$decoyName", decoyBytes + byteArrayOf(1), modified = mtime / SECOND * SECOND),
        )
        phone.hoard.report(root, entries)
        phone.hoard.upload(freedBytes)
        phone.hoard.upload(unprintedBytes)
        phone.hoard.upload(editedBytes)
        phone.hoard.upload(plainBytes)
        phone.hoard.upload(decoyBytes + byteArrayOf(1))
        assertThat(phone.hoard.report(root, entries).states()).containsExactly(
            "stored",
            "stored",
            "stored",
            "stored",
            "stored",
        )
        // ...then frees two files and the owner deletes three.
        val removed = phone.hoard.report(
            root,
            emptyList(),
            listOf(
                "DCIM/Camera/$freedName" to "freed",
                "DCIM/Camera/Trip/$unprintedName" to "freed",
                "DCIM/Camera/$editedName" to "deleted",
                "DCIM/Camera/$plainName" to "deleted",
                "DCIM/Camera/Trip/$decoyName" to "freed",
            ),
        )
        assertThat(removed.removedStates()).containsExactly("kept", "kept", "kept", "kept", "kept")
        // The server applies a report to its items a moment later (they read `onDevice: true` meanwhile, which
        // Reveal treats as "ask again later"): wait until every location says the file left the phone.
        val gone = entries.map { it.path }.toSet()
        awaitItems(phone, root, "the mirror locations say gone") { locations ->
            locations.filter { it.path in gone }.let { l -> l.size == gone.size && l.all { it.onDevice == false } }
        }
        // The real mirror locations: volume-relative paths (the query's `path` is the root's), `onDevice: false`, why.
        val listed = phone.reveal.api.items(root, "Trip", null, 50).items
        val location = listed.flatMap { it.files }.flatMap { it.locations }
            .single { it.source == "mirror" && it.path == "DCIM/Camera/Trip/$unprintedName" }
        assertThat(location.volume).isEqualTo("primary")
        assertThat(location.onDevice).isFalse()
        assertThat(location.gone).isEqualTo("freed")
        assertThat(location.rootId).isEqualTo(root)
        assertThat(location.physicalDeviceId).isEqualTo(phone.physicalDeviceId)

        // The walk finds the five files gone from the phone.
        runBlocking {
            val dao = phone.reveal.db.photoDao()
            for (id in listOf(freed, unprinted, edited, plain, decoy)) {
                dao.markVanished(dao.files(id).map { it.id }, phone.reveal.now)
            }
        }
        val report = phone.reveal.sync().freed

        assertThat(report.adopted).isEqualTo(3)
        assertThat(report.deleted).isEqualTo(2)
        val server = runBlocking { checkNotNull(phone.reveal.db.syncDao().serverSource()).id }
        val adopted = phone.reveal.photo(freed)
        assertThat(adopted.sourceId).isEqualTo(server)
        assertThat(adopted.uuid).isEqualTo(uuid)
        assertThat(adopted.folder).isEqualTo("DCIM/Camera")
        assertThat(phone.reveal.recipe(phone.reveal.version(freed)).light.exposureEv).isEqualTo(0.4f)
        val adoptedFile = runBlocking { phone.reveal.db.photoDao().files(freed) }.single()
        assertThat(adoptedFile.relativePath).startsWith(SyncStore.FREED_PREFIX)
        assertThat(adoptedFile.onServer).isTrue()
        // Found by its mirror location, size and second: the fingerprint is the server's.
        assertThat(phone.reveal.photo(unprinted).sourceId).isEqualTo(server)
        assertThat(runBlocking { phone.reveal.db.photoDao().files(unprinted) }.single().fingerprint)
            .isEqualTo(FileFingerprint.of(unprintedBytes))
        // Deleted on the phone: kept for its edit only.
        assertThat(phone.reveal.photo(edited).sourceId).isEqualTo(server)
        assertThat(phone.reveal.recipe(phone.reveal.version(edited)).light.exposureEv).isEqualTo(0.7f)
        assertThat(runBlocking { phone.reveal.db.photoDao().get(plain) }).isNull()
        assertThat(runBlocking { phone.reveal.db.photoDao().get(decoy) }).isNull()

        // Later syncs leave the adopted photos where they are.
        phone.sync()
        assertThat(phone.reveal.photo(freed).sourceId).isEqualTo(server)
        assertThat(phone.reveal.photo(unprinted).sourceId).isEqualTo(server)
    }

    @Test
    fun freedAdoptIsDeclaredToTheRealServer() {
        val phone = phone()
        val patches = Collections.synchronizedList(ArrayList<JsonObject>())
        val recording = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "PATCH" && request.url.encodedPath == "/v1/device") {
                val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                patches += SyncApi.json.parseToJsonElement(body).jsonObject
            }
            chain.proceed(request)
        }.build()
        val api = SyncApi(phone.hoard.url, phone.reveal.key, recording)
        val serverId = api.server().serverId
        // The first report learns this phone's physical device; freed-adopt needs it, so it comes with the next one.
        runBlocking { phone.reveal.engine.sync(api, serverId) }
        runBlocking { phone.reveal.engine.sync(api, serverId) }
        assertThat(runBlocking { phone.reveal.db.metaDao().get(SyncEngine.KEY_PHYSICAL_DEVICE) })
            .isEqualTo(phone.physicalDeviceId)
        val declared = patches.last()["features"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertThat(declared).containsAtLeast("culling", "freed-adopt")
        // Kept and visible to Hoard (its free-up gate, ADR 0024 "The Reveal gate", hoard-server 0.12.0 schema 11):
        // `features` of the device and `physicalDevice.devices[].features` of the grouped phone.
        assertThat(api.device().features).containsAtLeast("culling", "freed-adopt")
        val seen = phone.hoard.api.device().physicalDevice!!.devices.single { it.app == "reveal" }
        assertThat(seen.features).contains("freed-adopt")
    }

    // ------------------------------------------------------------------ Hoard's side, raw HTTP

    private data class Entry(
        val path: String,
        val fingerprint: String,
        val size: Long,
        val modifiedAt: Long,
        val sha256: String?,
    )

    private class Answer(val status: Int, val body: JsonObject) {
        fun results(): List<JsonObject> = body["results"]!!.jsonArray.map { it.jsonObject }

        fun states(): List<String> = results().sortedBy { it.int("index") }.map { it["state"]!!.jsonPrimitive.content }

        fun reasons(): List<String?> =
            results().sortedBy { it.int("index") }.map { it["reason"]?.jsonPrimitive?.content }

        fun removedStates(): List<String> =
            body["removed"]!!.jsonArray.map { it.jsonObject }.sortedBy { it.int("index") }
                .map { it["state"]!!.jsonPrimitive.content }

        private fun JsonObject.int(name: String) = this[name]!!.jsonPrimitive.content.toInt()
    }

    /** Hoard on the phone: the backup API as Hoard's `BackupWorker` uses it (Hoard ADR 0024). */
    private class Hoard(val url: String, key: String) {
        val api = SyncApi(url, key)
        private val auth = "Bearer $key"
        private val http = OkHttpClient()
        private val base = url.trimEnd('/')

        private fun call(method: String, path: String, body: JsonObject?): Answer {
            val request = Request.Builder().url("$base$path").header("Authorization", auth)
                .method(method, body?.toString()?.toRequestBody(JSON))
            http.newCall(request.build()).execute().use { response ->
                val text = response.body.string()
                val json = if (text.isBlank()) {
                    JsonObject(
                        emptyMap(),
                    )
                } else {
                    SyncApi.json.parseToJsonElement(text).jsonObject
                }
                return Answer(response.code, json)
            }
        }

        private fun ok(answer: Answer): Answer {
            check(answer.status in 200..299) { "${answer.status}: ${answer.body}" }
            return answer
        }

        private fun rootBody(volume: String, path: String, types: List<String>) = buildJsonObject {
            put("volume", volume)
            put("path", path)
            put("types", JsonArray(types.map(::JsonPrimitive)))
        }

        fun addRoot(volume: String, path: String, types: List<String>): String =
            ok(call("POST", "/v1/backup/roots", rootBody(volume, path, types))).body["id"]!!.jsonPrimitive.content

        fun addRootStatus(volume: String, path: String): Int =
            call("POST", "/v1/backup/roots", rootBody(volume, path, listOf("image"))).status

        fun pause(root: String, paused: Boolean) {
            ok(call("PATCH", "/v1/backup/roots/$root", buildJsonObject { put("paused", paused) }))
        }

        fun setTypes(root: String, types: List<String>) {
            ok(
                call(
                    "PATCH",
                    "/v1/backup/roots/$root",
                    buildJsonObject {
                        put("types", JsonArray(types.map(::JsonPrimitive)))
                    },
                ),
            )
        }

        fun report(root: String, entries: List<Entry>, removed: List<Pair<String, String>> = emptyList()): Answer = ok(
            call(
                "POST",
                "/v1/backup/report",
                buildJsonObject {
                    put("rootId", root)
                    put(
                        "entries",
                        buildJsonArray {
                            for (e in entries) {
                                add(
                                    buildJsonObject {
                                        put("path", e.path)
                                        put("fingerprint", e.fingerprint)
                                        put("size", e.size)
                                        put("modifiedAt", e.modifiedAt)
                                        e.sha256?.let { put("sha256", it) }
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "removed",
                        buildJsonArray {
                            for ((path, reason) in removed) {
                                add(
                                    buildJsonObject {
                                        put("path", path)
                                        put("reason", reason)
                                    },
                                )
                            }
                        },
                    )
                },
            ),
        )

        /** `POST /v1/uploads` as Hoard's backup sends it: `claim: false` (the mirror's, nobody's claim). */
        fun create(bytes: ByteArray, wait: Boolean = false): JsonObject = ok(
            call(
                "POST",
                "/v1/uploads",
                buildJsonObject {
                    put("sha256", BlobUploader.sha256(ByteArrayInputStream(bytes)))
                    put("size", bytes.size)
                    put("kind", "original")
                    put("fingerprint", FileFingerprint.of(bytes))
                    put("claim", false)
                    if (wait) put("wait", true)
                },
            ),
        ).body

        /** An upload made and left without its bytes: live for 10 minutes (Hoard ADR 0024 "`wait`"). */
        fun startUpload(bytes: ByteArray): String {
            val created = create(bytes)
            check(created["status"]!!.jsonPrimitive.content == "created") { created.toString() }
            return created["uploadId"]!!.jsonPrimitive.content
        }

        fun finish(uploadId: String, bytes: ByteArray) {
            val result = api.patchUpload(uploadId, 0, bytes.size) { it.write(bytes) }
            check(result.complete || result.offset == bytes.size.toLong())
        }

        fun upload(bytes: ByteArray) = finish(startUpload(bytes), bytes)

        fun abandon(uploadId: String) = api.deleteUpload(uploadId)

        private companion object {
            val JSON = "application/json".toMediaType()
        }
    }

    /** A document is `<dir>/<document id>`. */
    private class FileTree(private val dir: File) : DocumentTree {
        override val rootDocumentId = "root"

        override fun children(documentId: String) = emptyList<DocumentEntry>()

        override fun stat(documentId: String): DocumentEntry? = null

        override fun openInput(documentId: String, name: String): ImageInput =
            ImageInput(name, ParcelFileDescriptor.open(File(dir, documentId), ParcelFileDescriptor.MODE_READ_ONLY))

        override fun readBytes(documentId: String, maxBytes: Int): ByteArray = File(dir, documentId).readBytes()

        override fun writeBytes(documentId: String, bytes: ByteArray) = throw IOException("read only")

        override fun create(parentDocumentId: String, name: String, mimeType: String) = throw IOException("no")

        override fun uri(documentId: String): android.net.Uri = android.net.Uri.parse("file:///$documentId")
    }

    private companion object {
        const val HOUR = 60L * 60 * 1000
        const val SECOND = 1000L
        const val MTIME = 1_730_000_000_000L
        const val WAIT_MS = 30_000L
        const val POLL_MS = 250L
    }
}
