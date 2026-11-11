package dev.hfps.reveal.feature.export.metadata

import dev.hfps.reveal.data.export.Authorship
import dev.hfps.reveal.feature.export.model.MetadataLevel
import dev.hfps.reveal.feature.export.model.MetadataOptions

/**
 * The XMP packet of a JPEG export (docs/adr/0009): `xmp:CreatorTool`, `xmp:Rating` (level All), `dc:creator` and
 * `dc:rights` (UTF-8, unlike EXIF's ASCII). Null for level None.
 */
object XmpPacket {
    fun build(options: MetadataOptions, authorship: Authorship, rating: Int, software: String): String? {
        if (options.level == MetadataLevel.NONE) return null
        val attributes = buildString {
            append(" xmp:CreatorTool=\"").append(escape(software)).append('"')
            if (options.level == MetadataLevel.ALL &&
                rating in 1..MAX_RATING
            ) {
                append(" xmp:Rating=\"").append(rating).append('"')
            }
        }
        val body = buildString {
            if (authorship.author.isNotBlank()) {
                append("<dc:creator><rdf:Seq><rdf:li>").append(escape(authorship.author))
                append("</rdf:li></rdf:Seq></dc:creator>")
            }
            if (authorship.copyright.isNotBlank()) {
                append("<dc:rights><rdf:Alt><rdf:li xml:lang=\"x-default\">").append(escape(authorship.copyright))
                append("</rdf:li></rdf:Alt></dc:rights>")
            }
        }
        return "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>" +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"$attributes>$body</rdf:Description></rdf:RDF></x:xmpmeta>" +
            "<?xpacket end=\"w\"?>"
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val MAX_RATING = 5
}
