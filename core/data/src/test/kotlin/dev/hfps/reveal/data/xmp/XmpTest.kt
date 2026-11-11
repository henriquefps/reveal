package dev.hfps.reveal.data.xmp

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.synthetic.SyntheticShoot
import org.junit.Assert.assertThrows
import org.junit.Test

class XmpTest {
    private val date = "2026-11-11T10:00:00-03:00"

    private fun write(existing: String?, fields: XmpFields) =
        String(XmpWriter.update(existing?.toByteArray(), fields, "Reveal 0.2.0", date), Charsets.UTF_8)

    @Test
    fun `a new sidecar round-trips every field`() {
        for (flag in PickFlag.entries) {
            for (rating in 0..5) {
                val fields = XmpFields(rating = if (flag == PickFlag.REJECT) 0 else rating, label = "Red", flag = flag)
                assertThat(XmpReader.read(write(null, fields).toByteArray())).isEqualTo(fields)
            }
        }
    }

    @Test
    fun `a new sidecar is the standard Adobe shape`() {
        val text = write(null, XmpFields(rating = 4))
        assertThat(text).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"")
        assertThat(text).contains("xmp:Rating=\"4\"")
        assertThat(text).contains("xmp:CreatorTool=\"Reveal 0.2.0\"")
        assertThat(text).contains("xmp:MetadataDate=\"$date\"")
        assertThat(text).contains("digiKam:PickLabel=\"0\"")
        assertThat(text).contains("xmlns:digiKam=\"http://www.digikam.org/ns/1.0/\"")
    }

    @Test
    fun `reject is rating minus one and stars are dropped from the file`() {
        val text = write(null, XmpFields(rating = 3, flag = PickFlag.REJECT))
        assertThat(text).contains("xmp:Rating=\"-1\"")
        assertThat(text).contains("digiKam:PickLabel=\"1\"")
        assertThat(XmpReader.read(text.toByteArray())).isEqualTo(XmpFields(rating = 0, flag = PickFlag.REJECT))
    }

    @Test
    fun `hoard sidecars are picks with their stars`() {
        val hoard = SyntheticShoot.sidecar(rating = 3, creatorTool = "Hoard 1.7.2")
        assertThat(XmpReader.read(hoard.toByteArray())).isEqualTo(XmpFields(rating = 3, flag = PickFlag.PICK))
        // Unpicking in Reveal writes an explicit PickLabel 0, which wins over the Hoard rule from then on.
        val unpicked = write(hoard, XmpFields(rating = 3, flag = PickFlag.NONE))
        assertThat(unpicked).contains("xmp:CreatorTool=\"Hoard 1.7.2\"")
        assertThat(XmpReader.read(unpicked.toByteArray()).flag).isEqualTo(PickFlag.NONE)
    }

    @Test
    fun `lightroom sidecar keeps its develop settings when the rating changes`() {
        val updated = write(LIGHTROOM, XmpFields(rating = 2, label = "Green"))
        assertThat(XmpReader.read(LIGHTROOM.toByteArray())).isEqualTo(XmpFields(rating = 4, label = "Red"))
        assertThat(XmpReader.read(updated.toByteArray())).isEqualTo(XmpFields(rating = 2, label = "Green"))
        assertThat(updated).contains("crs:Exposure2012=\"+0.50\"")
        assertThat(updated).contains("<rdf:li>255, 255</rdf:li>")
        assertThat(updated).contains("xmlns:crs=\"http://ns.adobe.com/camera-raw-settings/1.0/\"")
        assertThat(updated).contains("x:xmptk=\"Adobe XMP Core 7.0-c000 1.000000, 0000/00/00-00:00:00        \"")
        assertThat(updated).doesNotContain("xmp:Rating=\"4\"")
        // Written twice: the document stays the same size (no accumulating whitespace or duplicates).
        val again = write(updated, XmpFields(rating = 2, label = "Green"))
        assertThat(again).isEqualTo(updated)
    }

    @Test
    fun `darktable sidecar keeps its history and reads reject as minus one`() {
        assertThat(XmpReader.read(DARKTABLE.toByteArray())).isEqualTo(XmpFields(rating = 0, flag = PickFlag.REJECT))
        val updated = write(DARKTABLE, XmpFields(rating = 5))
        assertThat(XmpReader.read(updated.toByteArray())).isEqualTo(XmpFields(rating = 5))
        assertThat(updated).contains("darktable:operation=\"exposure\"")
        assertThat(updated).contains("darktable:history_end=\"2\"")
        assertThat(updated).contains("<?xpacket end=\"w\"?>")
    }

    @Test
    fun `properties written as elements are read and replaced`() {
        val elements = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
             <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/">
              <xmp:Rating>3</xmp:Rating>
              <xmp:Label>Blue</xmp:Label>
             </rdf:Description></rdf:RDF></x:xmpmeta>
        """.trimIndent()
        assertThat(XmpReader.read(elements.toByteArray())).isEqualTo(XmpFields(rating = 3, label = "Blue"))
        val updated = write(elements, XmpFields(rating = 1))
        assertThat(updated).doesNotContain("<xmp:Rating>")
        assertThat(updated).doesNotContain("Blue")
        assertThat(XmpReader.read(updated.toByteArray())).isEqualTo(XmpFields(rating = 1))
    }

    @Test
    fun `a different prefix for the xmp namespace is reused`() {
        val xap =
            RDF_OPEN +
                """<rdf:Description rdf:about="" xmlns:xap="http://ns.adobe.com/xap/1.0/" xap:Rating="2"/>""" +
                """</rdf:RDF></x:xmpmeta>"""
        val updated = write(xap, XmpFields(rating = 5))
        assertThat(updated).contains("xap:Rating=\"5\"")
        assertThat(updated).doesNotContain("xmlns:xmp=")
    }

    @Test
    fun `labels are escaped and survive the round trip`() {
        val nasty = "A & B < C > \"D\" 'E'\ttab\nline\u0001ctl📷"
        val text = write(null, XmpFields(rating = 1, label = nasty))
        assertThat(text).contains("A &amp; B &lt; C &gt; &quot;D&quot; &apos;E&apos;&#x9;tab&#xA;line")
        assertThat(text).doesNotContain("\u0001")
        assertThat(XmpReader.read(text.toByteArray()).label).isEqualTo("A & B < C > \"D\" 'E'\ttab\nlinectl📷")
    }

    @Test
    fun `the reveal namespace is left as it was`() {
        val withReveal =
            RDF_OPEN +
                """<rdf:Description rdf:about="" xmlns:reveal="${XmpNs.REVEAL}" """ +
                """reveal:recipe="{&quot;v&quot;:1}"/>""" +
                """</rdf:RDF></x:xmpmeta>"""
        val updated = write(withReveal, XmpFields(rating = 2))
        assertThat(updated).contains("reveal:recipe=\"{&quot;v&quot;:1}\"")
        val extended = String(
            XmpWriter.update(
                null,
                XmpFields(),
                "Reveal",
                date,
                listOf(
                    XmpExtension { _, d ->
                        d.setAttributeNS(XmpNs.REVEAL, "reveal:x", "1")
                    },
                ),
            ),
        )
        assertThat(extended).contains("reveal:x=\"1\"")
    }

    @Test
    fun `malformed sidecars are refused, never overwritten`() {
        assertThrows(XmpException::class.java) { XmpWriter.update("<x:xmpmeta".toByteArray(), XmpFields(), "R", date) }
        assertThat(XmpReader.readOrNull("not xml".toByteArray())).isNull()
        // A document type declaration (XXE vector) is not processed.
        val xxe = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><x>&e;</x>"""
        assertThat(XmpReader.readOrNull(xxe.toByteArray())?.label).isNull()
    }

    @Test
    fun `out of range ratings are clamped and fractional ones truncated`() {
        val sidecar = SyntheticShoot.sidecar(rating = 9, creatorTool = "Other").replace("\"9\"", "\"7.0\"")
        assertThat(XmpReader.read(sidecar.toByteArray()).rating).isEqualTo(5)
        assertThat(
            XmpReader.read(SyntheticShoot.sidecar(rating = 2, pickLabel = 2, creatorTool = "Hoard").toByteArray()).flag,
        )
            .isEqualTo(PickFlag.NONE)
    }

    @Test
    fun `a byte order mark is accepted`() {
        val bom =
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                SyntheticShoot.sidecar(2, creatorTool = "X").toByteArray()
        assertThat(XmpReader.read(bom).rating).isEqualTo(2)
    }

    private companion object {
        const val RDF_OPEN =
            """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">"""

        val LIGHTROOM = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 7.0-c000 1.000000, 0000/00/00-00:00:00        ">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:xmp="http://ns.adobe.com/xap/1.0/"
                xmlns:tiff="http://ns.adobe.com/tiff/1.0/"
                xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
               xmp:ModifyDate="2026-08-08T21:10:11-03:00"
               xmp:Rating="4"
               xmp:Label="Red"
               tiff:Make="SONY"
               crs:Version="15.0"
               crs:Exposure2012="+0.50"
               crs:WhiteBalance="As Shot">
               <crs:ToneCurvePV2012>
                <rdf:Seq>
                 <rdf:li>0, 0</rdf:li>
                 <rdf:li>255, 255</rdf:li>
                </rdf:Seq>
               </crs:ToneCurvePV2012>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val DARKTABLE = """
            <?xpacket begin="${"\uFEFF"}" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="XMP Core 4.4.0-Exiv2">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:exif="http://ns.adobe.com/exif/1.0/"
                xmlns:xmp="http://ns.adobe.com/xap/1.0/"
                xmlns:xmpMM="http://ns.adobe.com/xap/1.0/mm/"
                xmlns:darktable="http://darktable.sf.net/"
               exif:DateTimeOriginal="2026:08:07 10:15:30.250"
               xmp:Rating="-1"
               xmpMM:DerivedFrom="DSC07668.ARW"
               darktable:xmp_version="5"
               darktable:raw_params="0"
               darktable:auto_presets_applied="1"
               darktable:history_end="2">
               <darktable:history>
                <rdf:Seq>
                 <rdf:li darktable:num="0" darktable:operation="exposure" darktable:enabled="1" darktable:params="0000000000000000"/>
                </rdf:Seq>
               </darktable:history>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end="w"?>
        """.trimIndent()
    }
}
