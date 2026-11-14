package dev.hfps.reveal.data.xmp

import dev.hfps.reveal.data.model.CullingField
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
 * A reject keeps its stars in the database only: XMP has one `xmp:Rating` and -1 wins. Reading a sidecar field by
 * field (what it holds, what it leaves out) is [SidecarCulling].
 */
data class XmpFields(val rating: Int = 0, val label: String? = null, val flag: PickFlag = PickFlag.NONE) {
    init {
        require(rating in 0..MAX_RATING) { "rating $rating outside 0..$MAX_RATING" }
    }

    companion object {
        const val MAX_RATING = 5
    }
}

/**
 * What a sidecar says about each culling field, field by field (Hoard ADR 0016 "The NAS sidecars", Reveal ADR 0027):
 * null = the sidecar says nothing about it. [rating] comes from a non-negative `xmp:Rating` only: `-1` is the file form
 * of a reject and leaves the stars unknown (a reject keeps its stars). [flag] from `digiKam:PickLabel` when present
 * (1 reject, 3 pick, other none), else from a negative rating, else a Hoard "Copy picks" sidecar (pick). [label] is
 * `xmp:Label` as written (blank = no label).
 */
data class SidecarCulling(val rating: Int? = null, val flag: PickFlag? = null, val label: String? = null) {
    /** True when the sidecar holds no culling value at all (it is then ignored). */
    val isEmpty: Boolean get() = rating == null && flag == null && label == null

    /**
     * The fields to import: those that differ from what the same sidecar held when last read ([last]; null = first
     * read: every field it holds). A label present last time and absent now was cleared (Lightroom removes
     * `xmp:Label`): it imports as no label. A field absent now and before, or unchanged, is not re-asserted.
     */
    fun changesSince(last: SidecarCulling?): CullingChanges = CullingChanges(
        rating = rating?.takeIf { last == null || it != last.rating },
        flag = flag?.takeIf { last == null || it != last.flag },
        label = when {
            label != null && (last == null || label != last.label) -> label.trim()
            label == null && last?.label != null -> ""
            else -> null
        },
    )

    /** [XmpFields] of these values over [base] (the photo's): what the sidecar leaves out keeps [base]'s. */
    fun over(base: XmpFields): XmpFields = XmpFields(
        rating = rating?.coerceIn(0, XmpFields.MAX_RATING) ?: base.rating,
        label = if (label != null) label.trim().takeIf { it.isNotEmpty() } else base.label,
        flag = flag ?: base.flag,
    )
}

/** Culling values to apply field by field; null = leave the field alone; [label] `""` = clear it. */
data class CullingChanges(val rating: Int? = null, val flag: PickFlag? = null, val label: String? = null) {
    val isEmpty: Boolean get() = rating == null && flag == null && label == null

    val fields: Set<CullingField>
        get() = buildSet {
            if (rating != null) add(CullingField.RATING)
            if (flag != null) add(CullingField.FLAG)
            if (label != null) add(CullingField.LABEL)
        }

    /** [base] with these changes applied. */
    fun over(base: XmpFields): XmpFields = XmpFields(
        rating = rating?.coerceIn(0, XmpFields.MAX_RATING) ?: base.rating,
        label = when (label) {
            null -> base.label
            "" -> null
            else -> label
        },
        flag = flag ?: base.flag,
    )
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

    /** Parses [bytes] field by field ([SidecarCulling]); throws [XmpException] when they are not well-formed XML. */
    fun readCulling(bytes: ByteArray): SidecarCulling = readCulling(XmpDom.parse(bytes))

    fun readCulling(document: Document): SidecarCulling {
        val raw = raw(document)
        val stars = raw.rating?.toInt()
        val flag = when {
            raw.pickLabel == PICK_LABEL_REJECT -> PickFlag.REJECT
            raw.pickLabel == PICK_LABEL_ACCEPT -> PickFlag.PICK
            raw.pickLabel != null -> PickFlag.NONE
            stars != null && stars < 0 -> PickFlag.REJECT
            raw.creatorTool?.trim()?.startsWith("Hoard") == true -> PickFlag.PICK
            else -> null
        }
        return SidecarCulling(
            rating = stars?.takeIf { it >= 0 }?.coerceAtMost(XmpFields.MAX_RATING),
            flag = flag,
            label = raw.label,
        )
    }

    private class Raw(val rating: Double?, val label: String?, val pickLabel: Int?, val creatorTool: String?)

    private fun raw(document: Document): Raw {
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
        return Raw(rating, label, pickLabel, creatorTool)
    }

    /** All three fields at once, a reject read as 0 stars (embedded packets; sidecars use [readCulling]). */
    fun read(document: Document): XmpFields {
        val raw = raw(document)
        val stars = raw.rating?.toInt() ?: 0
        val flag = when {
            stars < 0 -> PickFlag.REJECT
            raw.pickLabel == PICK_LABEL_REJECT -> PickFlag.REJECT
            raw.pickLabel == PICK_LABEL_ACCEPT -> PickFlag.PICK
            raw.pickLabel != null -> PickFlag.NONE
            raw.creatorTool?.trim()?.startsWith("Hoard") == true -> PickFlag.PICK
            else -> PickFlag.NONE
        }
        return XmpFields(
            rating = stars.coerceIn(0, XmpFields.MAX_RATING),
            label = raw.label?.takeIf { it.isNotBlank() },
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
 * Writes [XmpFields] into a sidecar. An existing sidecar is **merged**, not replaced: only the culling properties of
 * the fields asked for (ADR 0027: rating and flag share `xmp:Rating` + `digiKam:PickLabel`, label is `xmp:Label`) and
 * `xmp:MetadataDate` change; Lightroom's `crs:` develop settings,
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
     * (`Reveal 0.2.0`) is written only into a new sidecar; [date] is ISO 8601 (`xmp:MetadataDate`). [write] = the
     * culling fields the user changed: the others are left exactly as the sidecar has them (absent in a new one); an
     * edit-only write passes none.
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    fun update(
        existing: ByteArray?,
        fields: XmpFields,
        creatorTool: String,
        date: String,
        extensions: List<XmpExtension> = emptyList(),
        write: Set<CullingField> = CullingField.ALL,
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
        // Rating and flag share `xmp:Rating` (-1 = reject): writing either writes both properties of the pair.
        val stars = CullingField.RATING in write || CullingField.FLAG in write
        val label = CullingField.LABEL in write
        for (d in descriptions) {
            if (stars) {
                XmpDom.removeProperty(d, XmpNs.XMP, "Rating")
                XmpDom.removeProperty(d, XmpNs.DIGIKAM, "PickLabel")
            }
            if (label) XmpDom.removeProperty(d, XmpNs.XMP, "Label")
            XmpDom.removeProperty(d, XmpNs.XMP, "MetadataDate")
        }
        if (stars) {
            val rating = if (fields.flag == PickFlag.REJECT) -1 else fields.rating
            XmpDom.setAttribute(target, XmpNs.XMP, "Rating", rating.toString())
            val pick = when (fields.flag) {
                PickFlag.NONE -> PICK_LABEL_NONE
                PickFlag.PICK -> PICK_LABEL_ACCEPT
                PickFlag.REJECT -> PICK_LABEL_REJECT
            }
            XmpDom.setAttribute(target, XmpNs.DIGIKAM, "PickLabel", pick)
        }
        if (label) fields.label?.takeIf { it.isNotBlank() }?.let { XmpDom.setAttribute(target, XmpNs.XMP, "Label", it) }
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
