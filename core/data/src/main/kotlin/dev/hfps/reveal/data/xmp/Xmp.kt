package dev.hfps.reveal.data.xmp

import dev.hfps.reveal.data.model.PickFlag
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * The library fields that travel through XMP (ADR 0006, "XMP"). Shared with Hoard (its ADR 0009 section 9),
 * Lightroom, darktable and digiKam:
 *
 * - [rating] 0..5: `xmp:Rating`, the Adobe property every editor reads.
 * - [flag] REJECT: `xmp:Rating="-1"`; Lightroom, darktable and Hoard (`PhotoMetadata.rating`) agree on -1.
 * - [flag] PICK / REJECT / NONE: `digiKam:PickLabel` 3 / 1 / 0, the only pick flag in common use (digiKam); written
 *   so a pick survives the round trip.
 * - [flag] PICK, read only: a sidecar whose `xmp:CreatorTool` starts with `Hoard` and has no `digiKam:PickLabel`
 *   (Hoard writes sidecars only for the shots "Copy picks" copies).
 * - [label]: `xmp:Label`, free text; Lightroom shows "Red", "Yellow", ... as colours.
 *
 * A reject keeps its stars in the database only: XMP has one `xmp:Rating` and -1 wins.
 */
data class XmpFields(val rating: Int = 0, val label: String? = null, val flag: PickFlag = PickFlag.NONE) {
    init {
        require(rating in 0..MAX_RATING) { "rating $rating outside 0..$MAX_RATING" }
    }

    companion object {
        const val MAX_RATING = 5
    }
}

/** A sidecar or packet that is not well-formed XML, or not XMP: never overwritten. */
class XmpException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Namespaces Reveal reads or writes. */
object XmpNs {
    const val X = "adobe:ns:meta/"
    const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    const val XMP = "http://ns.adobe.com/xap/1.0/"
    const val DIGIKAM = "http://www.digikam.org/ns/1.0/"
    const val XMLNS = "http://www.w3.org/2000/xmlns/"

    /** Adobe Camera Raw settings (Lightroom, Camera Raw; darktable's Lightroom import reads some): ADR 0026. */
    const val CRS = "http://ns.adobe.com/camera-raw-settings/1.0/"

    /** `tiff:Orientation`: the picture's orientation after the user's quarter turns and flips (ADR 0026). */
    const val TIFF = "http://ns.adobe.com/tiff/1.0/"

    /** Reveal's own namespace: the recipe and its ancestry in the sidecar (ADR 0026, [XmpDevelop]). */
    const val REVEAL = "https://reveal.hfps.dev/ns/1.0/"

    /** Preferred prefixes when a namespace has to be declared. */
    val prefixes = mapOf(XMP to "xmp", DIGIKAM to "digiKam", REVEAL to "reveal", CRS to "crs", TIFF to "tiff")
}

/** Reads [XmpFields] from a sidecar or an embedded packet. */
object XmpReader {
    private const val PICK_LABEL_REJECT = 1
    private const val PICK_LABEL_ACCEPT = 3

    /** Parses [bytes]; throws [XmpException] when they are not well-formed XML. */
    fun read(bytes: ByteArray): XmpFields = read(XmpDom.parse(bytes))

    /** Like [read], or null for anything unreadable (an embedded packet is best effort). */
    fun readOrNull(bytes: ByteArray): XmpFields? = runCatching { read(bytes) }.getOrNull()

    fun read(document: Document): XmpFields {
        var rating: Double? = null
        var label: String? = null
        var pickLabel: Int? = null
        var creatorTool: String? = null
        for (description in XmpDom.descriptions(document)) {
            rating = rating ?: XmpDom.property(description, XmpNs.XMP, "Rating")?.trim()?.toDoubleOrNull()
            label = label ?: XmpDom.property(description, XmpNs.XMP, "Label")
            pickLabel = pickLabel ?: XmpDom.property(description, XmpNs.DIGIKAM, "PickLabel")?.trim()?.toIntOrNull()
            creatorTool = creatorTool ?: XmpDom.property(description, XmpNs.XMP, "CreatorTool")
        }
        val stars = rating?.toInt() ?: 0
        val flag = when {
            stars < 0 -> PickFlag.REJECT
            pickLabel == PICK_LABEL_REJECT -> PickFlag.REJECT
            pickLabel == PICK_LABEL_ACCEPT -> PickFlag.PICK
            pickLabel != null -> PickFlag.NONE
            creatorTool?.trim()?.startsWith("Hoard") == true -> PickFlag.PICK
            else -> PickFlag.NONE
        }
        return XmpFields(
            rating = stars.coerceIn(0, XmpFields.MAX_RATING),
            label = label?.takeIf { it.isNotBlank() },
            flag = flag,
        )
    }
}

/**
 * Something else Reveal writes into the sidecar's main `rdf:Description` (the recipe and the Camera Raw overlap,
 * [XmpDevelop]). It receives the element after the library fields are set.
 */
fun interface XmpExtension {
    fun apply(document: Document, description: Element)
}

/**
 * Writes [XmpFields] into a sidecar. An existing sidecar is **merged**, not replaced: only `xmp:Rating`,
 * `xmp:Label`, `digiKam:PickLabel` and `xmp:MetadataDate` change; Lightroom's `crs:` develop settings,
 * darktable's history and anything else stay as they were (same element order, comments and processing
 * instructions). A sidecar that does not parse is refused ([XmpException]); the caller keeps the change in the
 * database only.
 */
object XmpWriter {
    private const val PICK_LABEL_NONE = "0"
    private const val PICK_LABEL_REJECT = "1"
    private const val PICK_LABEL_ACCEPT = "3"

    /**
     * The new sidecar bytes (UTF-8, no BOM). [existing] = the current sidecar, or null to create one; [creatorTool]
     * (`Reveal 0.2.0`) is written only into a new sidecar; [date] is ISO 8601 (`xmp:MetadataDate`).
     */
    fun update(
        existing: ByteArray?,
        fields: XmpFields,
        creatorTool: String,
        date: String,
        extensions: List<XmpExtension> = emptyList(),
    ): ByteArray {
        val document = if (existing == null || existing.all { it.toInt().toChar().isWhitespace() }) {
            XmpDom.parse(newSidecar(creatorTool).toByteArray(Charsets.UTF_8))
        } else {
            XmpDom.parse(existing)
        }
        val descriptions = XmpDom.descriptions(document)
        if (descriptions.isEmpty()) throw XmpException("No rdf:Description in the sidecar")
        // The library fields live in one description: the one that already has them, else the first.
        val target = descriptions.firstOrNull { d ->
            XmpDom.property(d, XmpNs.XMP, "Rating") != null || XmpDom.property(d, XmpNs.XMP, "Label") != null
        } ?: descriptions.first()
        for (d in descriptions) {
            XmpDom.removeProperty(d, XmpNs.XMP, "Rating")
            XmpDom.removeProperty(d, XmpNs.XMP, "Label")
            XmpDom.removeProperty(d, XmpNs.DIGIKAM, "PickLabel")
            XmpDom.removeProperty(d, XmpNs.XMP, "MetadataDate")
        }
        val rating = if (fields.flag == PickFlag.REJECT) -1 else fields.rating
        XmpDom.setAttribute(target, XmpNs.XMP, "Rating", rating.toString())
        fields.label?.takeIf { it.isNotBlank() }?.let { XmpDom.setAttribute(target, XmpNs.XMP, "Label", it) }
        val pick = when (fields.flag) {
            PickFlag.NONE -> PICK_LABEL_NONE
            PickFlag.PICK -> PICK_LABEL_ACCEPT
            PickFlag.REJECT -> PICK_LABEL_REJECT
        }
        XmpDom.setAttribute(target, XmpNs.DIGIKAM, "PickLabel", pick)
        XmpDom.setAttribute(target, XmpNs.XMP, "MetadataDate", date)
        extensions.forEach { it.apply(document, target) }
        return XmlSerializer.serialize(document).toByteArray(Charsets.UTF_8)
    }

    private fun newSidecar(creatorTool: String): String {
        val tool = XmlSerializer.escapeAttribute(creatorTool)
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="$tool">
            | <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            |  <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:CreatorTool="$tool"/>
            | </rdf:RDF>
            |</x:xmpmeta>
            |
        """.trimMargin()
    }
}

/** DOM helpers for XMP: parsing without DTDs or external entities, and RDF properties as attribute or element. */
internal object XmpDom {
    fun parse(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            isIgnoringComments = false
            // XXE hardening; not every platform parser knows every feature, so each one is best effort.
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        return try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(stripBom(bytes)))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            throw XmpException("Not well-formed XMP: ${e.message}", e)
        }
    }

    private fun stripBom(bytes: ByteArray): ByteArray {
        val bom = bytes.size >= BOM.size && BOM.indices.all { bytes[it] == BOM[it] }
        return if (bom) bytes.copyOfRange(BOM.size, bytes.size) else bytes
    }

    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun descriptions(document: Document): List<Element> {
        val list = document.getElementsByTagNameNS(XmpNs.RDF, "Description")
        return (0 until list.length).map { list.item(it) as Element }
    }

    /** A simple property: `ns:name="v"` on the description, or `<ns:name>v</ns:name>` inside it. */
    fun property(description: Element, ns: String, name: String): String? {
        description.getAttributeNodeNS(ns, name)?.let { return it.value }
        return children(description).firstOrNull { it.namespaceURI == ns && it.localName == name }?.textContent
    }

    fun removeProperty(description: Element, ns: String, name: String) {
        description.getAttributeNodeNS(ns, name)?.let { description.removeAttributeNode(it) }
        children(description).filter { it.namespaceURI == ns && it.localName == name }.forEach { child ->
            // Drop the indentation before the element too, so repeated writes do not accumulate blank lines.
            val before = child.previousSibling
            if (before != null && before.nodeType == Node.TEXT_NODE && before.nodeValue.isBlank()) {
                description.removeChild(before)
            }
            description.removeChild(child)
        }
    }

    /** An `rdf:Seq` (or `rdf:Bag`/`rdf:Alt`) property's items, or null when the property is absent or not a list. */
    @Suppress("ReturnCount")
    fun list(description: Element, ns: String, name: String): List<String>? {
        val property =
            children(description).firstOrNull { it.namespaceURI == ns && it.localName == name } ?: return null
        val container = children(property).firstOrNull { it.namespaceURI == XmpNs.RDF } ?: return null
        return children(container).filter {
            it.namespaceURI == XmpNs.RDF && it.localName == "li"
        }.map { it.textContent }
    }

    /** Removes every property of [ns] (attributes and elements) from [description]. */
    fun removeNamespace(description: Element, ns: String) {
        attributes(description).filter { it.namespaceURI == ns }.forEach { description.removeAttributeNode(it) }
        children(description).filter { it.namespaceURI == ns }.forEach { removeProperty(description, ns, it.localName) }
    }

    /** True when [description] has any property of [ns]. */
    fun hasNamespace(description: Element, ns: String): Boolean =
        attributes(description).any { it.namespaceURI == ns } || children(description).any { it.namespaceURI == ns }

    /** Appends `<ns:name>text</ns:name>` (a property too long or structured for an attribute). */
    fun appendText(description: Element, ns: String, name: String, text: String) {
        description.appendChild(propertyElement(description, ns, name).apply { textContent = text })
    }

    /** Appends `<ns:name><rdf:Seq><rdf:li>item</rdf:li>...</rdf:Seq></ns:name>`. */
    fun appendSeq(description: Element, ns: String, name: String, items: List<String>) {
        val document = description.ownerDocument
        val property = propertyElement(description, ns, name)
        val rdf = description.lookupPrefix(XmpNs.RDF) ?: "rdf"
        val seq = document.createElementNS(XmpNs.RDF, "$rdf:Seq")
        for (item in items) seq.appendChild(document.createElementNS(XmpNs.RDF, "$rdf:li").apply { textContent = item })
        property.appendChild(seq)
        description.appendChild(property)
    }

    private fun propertyElement(description: Element, ns: String, name: String): Element {
        val prefix = description.lookupPrefix(ns) ?: declare(description, ns)
        // One indentation step deeper than the description, as XMP tools lay sidecars out; the closing indentation
        // moves to the end ([closeIndent]), so repeated writes never accumulate blank lines.
        val last = description.lastChild
        if (last != null && last.nodeType == Node.TEXT_NODE && last.nodeValue.isBlank()) description.removeChild(last)
        description.appendChild(description.ownerDocument.createTextNode("\n" + " ".repeat(depth(description) + 1)))
        return description.ownerDocument.createElementNS(ns, "$prefix:$name")
    }

    /** Ends [description]'s children with the indentation of its closing tag (after [appendText]/[appendSeq]). */
    fun closeIndent(description: Element) {
        val last = description.lastChild ?: return
        if (last.nodeType == Node.ELEMENT_NODE) {
            description.appendChild(description.ownerDocument.createTextNode("\n" + " ".repeat(depth(description))))
        }
    }

    private fun depth(element: Element): Int {
        var n = 0
        var node: Node? = element.parentNode
        while (node != null && node.nodeType == Node.ELEMENT_NODE) {
            n++
            node = node.parentNode
        }
        return n
    }

    fun setAttribute(element: Element, ns: String, name: String, value: String) {
        val prefix = element.lookupPrefix(ns) ?: declare(element, ns)
        element.setAttributeNS(ns, "$prefix:$name", value)
    }

    private fun declare(element: Element, ns: String): String {
        val wanted = XmpNs.prefixes[ns] ?: "ns"
        var prefix = wanted
        var n = 1
        while (element.lookupNamespaceURI(prefix) != null) prefix = "$wanted${n++}"
        element.setAttributeNS(XmpNs.XMLNS, "xmlns:$prefix", ns)
        return prefix
    }

    fun children(element: Element): List<Element> {
        val nodes = element.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    /** Attributes in document order, namespace declarations first (as XMP tools write them). */
    fun attributes(element: Element): List<Attr> {
        val map = element.attributes
        val all = (0 until map.length).map { map.item(it) as Attr }
        return all.filter { it.isNamespaceDeclaration() } + all.filterNot { it.isNamespaceDeclaration() }
    }

    private fun Attr.isNamespaceDeclaration() =
        namespaceURI == XmpNs.XMLNS || name == "xmlns" || name.startsWith("xmlns:")
}
