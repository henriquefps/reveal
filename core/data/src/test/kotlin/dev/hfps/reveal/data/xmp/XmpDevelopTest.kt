package dev.hfps.reveal.data.xmp

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.LocalAdjustment
import dev.hfps.reveal.pipeline.recipe.LocalAdjustments
import dev.hfps.reveal.pipeline.recipe.Mask
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeCodec
import dev.hfps.reveal.pipeline.recipe.WbMode
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import org.junit.Assert.assertThrows
import org.junit.Test

class XmpDevelopTest {
    private val frame = PhotoFrame(raw = true, orientation = 1, width = 6000, height = 4000)

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("xmp/$name")) { name }.readBytes()

    private val recipe = Recipe(
        light = Light(exposureEv = 0.734f, contrast = 12.5f, shadows = 30f),
        colour = Colour(whiteBalance = WhiteBalance(temperatureK = 5123.4f, tint = 7.25f, mode = WbMode.CUSTOM)),
        geometry = Geometry(crop = CropRect(0.1f, 0.12f, 0.88f, 0.9f), angle = 1.3f, rotate = 1),
        masks = listOf(
            Mask(
                id = "m1",
                name = "Sky",
                adjustments = LocalAdjustments().with(LocalAdjustment.EXPOSURE, -0.4f),
                components = listOf(MaskComponent.Linear(), MaskComponent.Radial(cx = 0.3f, invert = true)),
            ),
        ),
    )

    private fun state(r: Recipe = recipe, ancestors: List<String> = listOf("0123456789abcdef")) =
        DevelopState(r, frame, ancestors, edited = 1_778_000_000_000, device = "SM-S928B")

    private fun write(existing: ByteArray?, state: DevelopState?, fields: XmpFields = XmpFields(rating = 3)) =
        XmpWriter.update(existing, fields, "Reveal 1.0.0", "2026-11-13T10:00:00Z", listOf(XmpDevelop.extension(state)))

    private fun read(bytes: ByteArray) = XmpDevelop.read(XmpDom.parse(bytes))

    @Test
    fun `Reveal to XMP to Reveal gives the identical recipe`() {
        val bytes = write(null, state())
        val block = read(bytes).reveal!!
        assertThat(block.recipe).isEqualTo(recipe)
        assertThat(block.newer).isFalse()
        assertThat(block.device).isEqualTo("SM-S928B")
        assertThat(block.edited).isEqualTo(1_778_000_000_000)
        assertThat(block.ancestors).containsExactly("0123456789abcdef")
        // Twice: the same document, no accumulated whitespace or duplicates.
        val again = write(bytes, state())
        assertThat(String(again)).isEqualTo(String(bytes))
    }

    @Test
    fun `the recipe is compact and its digest ignores fields at their defaults`() {
        val text = SidecarRecipe.encode(Recipe(light = Light(exposureEv = 0.5f)))
        assertThat(text).isEqualTo("""{"v":1,"light":{"exposure":0.5}}""")
        assertThat(SidecarRecipe.decode(text)).isEqualTo(Recipe(light = Light(exposureEv = 0.5f)))
        assertThat(SidecarRecipe.encode(Recipe())).isEqualTo("""{"v":1}""")
        val full = RecipeCodec.Default.encode(Recipe(light = Light(exposureEv = 0.5f)))
        assertThat(
            SidecarRecipe.digest(SidecarRecipe.decode(full)),
        ).isEqualTo(SidecarRecipe.digest(Recipe(light = Light(exposureEv = 0.5f))))
        assertThat(SidecarRecipe.digest(Recipe())).hasLength(16)
        assertThat(SidecarRecipe.digest(Recipe())).isNotEqualTo(SidecarRecipe.digest(recipe))
    }

    @Test
    fun `a large recipe is stored deflated and reads back`() {
        val strokes = (0 until 400).map { i ->
            MaskComponent.Radial(cx = (i % 100) / 100f, cy = 0.5f, rx = 0.01f, ry = 0.01f)
        }
        val big = recipe.copy(
            masks = listOf(
                Mask(
                    id = "big",
                    components = strokes,
                    adjustments = LocalAdjustments().with(LocalAdjustment.EXPOSURE, 1f),
                ),
            ),
        )
        assertThat(SidecarRecipe.encode(big).length).isGreaterThan(SidecarRecipe.DEFLATE_ABOVE)
        val bytes = write(null, state(big))
        assertThat(String(bytes)).contains("reveal:RecipeDeflate")
        assertThat(read(bytes).reveal!!.recipe).isEqualTo(big)
    }

    @Test
    fun `fields Lightroom and darktable read are written in the standard namespaces`() {
        val text = String(write(null, state()))
        val develop = read(text.toByteArray())
        assertThat(develop.crs["Exposure2012"]).isEqualTo("+0.73")
        assertThat(develop.crs["Contrast2012"]).isEqualTo("+13")
        assertThat(develop.crs["Temperature"]).isEqualTo("5123")
        assertThat(develop.crs["HasCrop"]).isEqualTo("True")
        assertThat(develop.crs["CropAngle"]).isEqualTo("+1.30")
        assertThat(develop.crs["ToneCurvePV2012"]).isEqualTo("0, 0;255, 255")
        assertThat(develop.orientation).isEqualTo(6)
        assertThat(text).contains("crs:ProcessVersion=\"11.0\"")
        assertThat(text).contains("crs:HasSettings=\"True\"")
        assertThat(text).contains("xmlns:crs=\"http://ns.adobe.com/camera-raw-settings/1.0/\"")
        assertThat(
            text,
        ).contains("<crs:ToneCurvePV2012><rdf:Seq><rdf:li>0, 0</rdf:li><rdf:li>255, 255</rdf:li></rdf:Seq>")
        assertThat(XmpReader.read(text.toByteArray())).isEqualTo(XmpFields(rating = 3))
    }

    @Test
    fun `merging into a Lightroom sidecar keeps everything Reveal does not manage`() {
        val original = String(fixture("lightroom-classic.xmp"))
        val merged = String(write(fixture("lightroom-classic.xmp"), state(), XmpFields(rating = 4, label = "Red")))
        // Kept: other namespaces and the crs fields outside the mapping.
        for (kept in listOf(
            "crs:CameraProfile=\"Adobe Color\"",
            "crs:LensProfileName=\"Adobe (SONY E PZ 16-50mm F3.5-5.6 OSS)\"",
            "crs:CropConstrainToWarp=\"1\"",
            "crs:AlreadyApplied=\"False\"",
            "crs:Version=\"16.0\"",
            "tiff:Make=\"SONY\"",
            "aux:Lens=\"E PZ 16-50mm F3.5-5.6 OSS\"",
            "<rdf:li>beach</rdf:li>",
            "stEvt:softwareAgent=\"Adobe Photoshop Lightroom Classic 14.0 (Windows)\"",
        )) {
            assertThat(original).contains(kept)
            assertThat(merged).contains(kept)
        }
        // Replaced: the mapped values now say Reveal's.
        val develop = read(merged.toByteArray())
        assertThat(develop.crs["Exposure2012"]).isEqualTo("+0.73")
        assertThat(develop.crs["Highlights2012"]).isEqualTo("0")
        assertThat(develop.crs["ProcessVersion"]).isEqualTo("11.0")
        assertThat(develop.reveal!!.recipe).isEqualTo(recipe)
        assertThat(Regex("crs:Exposure2012").findAll(merged).count()).isEqualTo(1)
        assertThat(Regex("<crs:ToneCurvePV2012>").findAll(merged).count()).isEqualTo(1)
        assertThat(Regex("<crs:ToneCurvePV2012Red>").findAll(merged).count()).isEqualTo(1)
    }

    @Test
    fun `darktable's history survives value for value`() {
        val merged = write(fixture("darktable.xmp"), state())
        fun history(bytes: ByteArray): List<Map<String, String>> {
            val items = XmpDom.parse(bytes).getElementsByTagNameNS(XmpNs.RDF, "li")
            return (0 until items.length).map { i ->
                val attrs = items.item(i).attributes
                (0 until attrs.length).associate { attrs.item(it).nodeName to attrs.item(it).nodeValue }
            }.filter { it.isNotEmpty() }
        }
        assertThat(history(merged)).isEqualTo(history(fixture("darktable.xmp")))
        assertThat(history(merged)).hasSize(2)
        val text = String(merged)
        assertThat(text).contains("darktable:history_end=\"2\"")
        assertThat(text).contains("darktable:multi_name=\"&amp; &lt;basic&gt;\"")
        assertThat(text).contains("<darktable:masks_history>")
        // Element order kept: darktable's lists before the elements Reveal appends.
        assertThat(text.indexOf("<darktable:colorlabels>")).isLessThan(text.indexOf("<crs:ToneCurvePV2012>"))
    }

    @Test
    fun `a Hoard sidecar keeps its pick and gains the edit`() {
        val merged = write(fixture("hoard.xmp"), state(), XmpFields(rating = 4, flag = PickFlag.PICK))
        assertThat(XmpReader.read(merged)).isEqualTo(XmpFields(rating = 4, flag = PickFlag.PICK))
        assertThat(String(merged)).contains("xmp:CreatorTool=\"Hoard 1.9.0\"")
        assertThat(read(merged).reveal!!.recipe).isEqualTo(recipe)
    }

    @Test
    fun `reading the samples`() {
        val classic = read(fixture("lightroom-classic.xmp"))
        assertThat(classic.reveal).isNull()
        assertThat(classic.hasEdits).isTrue()
        assertThat(classic.crs["Exposure2012"]).isEqualTo("+0.70")
        assertThat(classic.crs["ToneCurvePV2012Red"]).isEqualTo("0, 0;255, 255")
        assertThat(classic.crs).doesNotContainKey("CameraProfile")
        assertThat(classic.orientation).isEqualTo(1)
        val mobile = read(fixture("lightroom-mobile.xmp"))
        assertThat(mobile.crs["Exposure2012"]).isEqualTo("-0.35")
        assertThat(mobile.crs["ToneCurvePV2012"]).isEqualTo("0, 20;128, 140;255, 255")
        assertThat(mobile.orientation).isEqualTo(6)
        assertThat(read(fixture("darktable.xmp")).hasEdits).isFalse()
        assertThat(read(fixture("hoard.xmp")).hasEdits).isFalse()
    }

    @Test
    fun `nothing is written without a state, and an unedited photo only updates an existing block`() {
        val plain = write(fixture("hoard.xmp"), null)
        assertThat(String(plain)).doesNotContain("reveal:")
        assertThat(String(plain)).doesNotContain("crs:")
        val unedited = state(Recipe()).copy(onlyIfPresent = true)
        assertThat(String(write(fixture("lightroom-classic.xmp"), unedited))).contains("crs:Exposure2012=\"+0.70\"")
        val withBlock = write(null, state())
        val reset = read(write(withBlock, unedited))
        assertThat(reset.reveal!!.recipe).isEqualTo(Recipe())
        assertThat(reset.crs["Exposure2012"]).isEqualTo("0.00")
    }

    @Test
    fun `a newer app's block is never overwritten`() {
        val newer = String(write(null, state())).replace("reveal:Schema=\"1\"", "reveal:Schema=\"2\"").toByteArray()
        val block = read(newer).reveal!!
        assertThat(block.newer).isTrue()
        assertThat(block.recipe).isNull()
        val merged = String(write(newer, state(Recipe(light = Light(exposureEv = -1f)))))
        assertThat(merged).contains("reveal:Schema=\"2\"")
        assertThat(merged).contains("crs:Exposure2012=\"+0.73\"")
        val newerRecipe = String(write(null, state())).replace(Regex("""\{"v":1,"""), """{"v":99,""").toByteArray()
        assertThat(read(newerRecipe).reveal!!.newer).isTrue()
    }

    @Test
    fun `hostile and malformed blocks read as absent, never throw`() {
        fun sidecar(body: String) =
            """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="" xmlns:reveal="https://reveal.hfps.dev/ns/1.0/" $body</rdf:Description>
            </rdf:RDF></x:xmpmeta>""".toByteArray()
        val notJson = read(sidecar("""reveal:Schema="1"><reveal:Recipe>{"v":1,"light":</reveal:Recipe>"""))
        assertThat(notJson.reveal!!.recipe).isNull()
        val invalid = read(sidecar("""><reveal:Recipe>{"v":1,"geometry":{"rotate":9}}</reveal:Recipe>"""))
        assertThat(invalid.reveal!!.recipe).isNull()
        val badBase64 = read(sidecar("""><reveal:RecipeDeflate>@@@not base64@@@</reveal:RecipeDeflate>"""))
        assertThat(badBase64.reveal!!.recipe).isNull()
        // A deflate bomb: 64 MiB of zeros in a few kilobytes.
        val bomb = SidecarRecipe.deflate("0".repeat(64 * 1024 * 1024))
        assertThat(bomb.length).isLessThan(200_000)
        assertThat(SidecarRecipe.inflate(bomb)).isNull()
        val weird = read(
            sidecar(
                """reveal:Edited="yesterday" reveal:Ancestors="zz 0123456789abcdef ${"a".repeat(
                    5000,
                )}" reveal:Device="${"x".repeat(500)}">""" +
                    """<reveal:Recipe>{"v":1}</reveal:Recipe>""",
            ),
        )
        assertThat(weird.reveal!!.edited).isNull()
        assertThat(weird.reveal.ancestors).containsExactly("0123456789abcdef")
        assertThat(weird.reveal.device).hasLength(80)
        assertThat(weird.reveal.recipe).isEqualTo(Recipe())
        val doctype = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]>
            <x:xmpmeta xmlns:x="adobe:ns:meta/">&e;</x:xmpmeta>""".toByteArray()
        assertThrows(XmpException::class.java) { XmpDom.parse(doctype) }
        assertThrows(XmpException::class.java) { write(doctype, state()) }
    }
}
