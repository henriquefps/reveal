// ISO/IEC 14496-12 (ISOBMFF), 23008-12 (HEIF) and the AVIF specification: box layouts are the specifications'.
@file:Suppress("MagicNumber")

package dev.hfps.image.write

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** The coded image format of a HEIF-family file. */
enum class ImageCodec(val itemType: String, val brand: String, val configBox: String) {
    /** HEIC: H.265 tiles, `hvcC`. */
    HEVC("hvc1", "heic", "hvcC"),

    /** AVIF: AV1 tiles, `av1C`. */
    AV1("av01", "avif", "av1C"),
}

/**
 * How the coded samples are to be read as colour (the `colr` boxes): `nclx` code points (ITU-T H.273) and the ICC
 * profile of the RGB space. Exports use [primaries] 1 (sRGB/BT.709) or 12 (Display P3, SMPTE EG 432-1), [transfer]
 * 13 (sRGB), [matrix] 1 (BT.709 Y'CbCr), full range.
 */
class ColourInfo(val primaries: Int, val transfer: Int, val matrix: Int, val fullRange: Boolean, val icc: ByteArray?) {
    companion object {
        const val PRIMARIES_BT709 = 1
        const val PRIMARIES_P3_D65 = 12
        const val TRANSFER_SRGB = 13
        const val MATRIX_BT709 = 1

        /** The colour of [profile] as the export writes it. */
        fun of(profile: OutputProfile): ColourInfo = ColourInfo(
            primaries = if (profile == OutputProfile.DISPLAY_P3) PRIMARIES_P3_D65 else PRIMARIES_BT709,
            transfer = TRANSFER_SRGB,
            matrix = MATRIX_BT709,
            fullRange = true,
            icc = IccProfiles.of(profile),
        )
    }
}

/** A coded picture in a grid of equal tiles (row-major [tiles] samples), as an encoder produced it. */
@Suppress("LongParameterList")
class CodedImage(
    val codec: ImageCodec,
    /** The `hvcC` / `av1C` payload shared by every tile. */
    val config: ByteArray,
    val width: Int,
    val height: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val columns: Int,
    val rows: Int,
    val bitDepth: Int,
    val tiles: List<ByteArray>,
) {
    init {
        require(tiles.size == columns * rows) { "${tiles.size} tiles for a $columns x $rows grid" }
        require(columns * tileWidth >= width && rows * tileHeight >= height) { "the grid does not cover the image" }
    }
}

/**
 * Writes HEIF (HEIC) and AVIF still images (docs/adr/0009 "HEIF and AVIF"): `ftyp`, then `meta` (`hdlr pict`, `pitm`
 * = a `grid` derived item over the tiles (1 x 1 when it fits one), `iinf`, `iref` (`dimg` grid -> tiles, `cdsc` Exif
 * and XMP -> grid), `iprp` with the codec config, `ispe`, `pixi`, and **two `colr` boxes**: `nclx` (primaries,
 * transfer, matrix, range) and `prof` (the ICC profile), on the grid and on every tile; `iloc`), then `mdat` (grid
 * descriptor, tiles, the Exif item = 4-byte offset + `Exif\0\0` + TIFF, the XMP packet as a `mime` item).
 *
 * Android's own encoders (`HeifWriter`, MediaMuxer) cannot label Display P3 or carry an ICC profile; this writer
 * owns the container so the colour is explicit. Pure Kotlin, JVM-tested and checked with libavif / ffmpeg.
 */
object HeifContainer {
    private const val GRID_ID = 1
    private const val FIRST_TILE_ID = 2

    /** Writes [image] with [colour], EXIF ([exifTiff], the TIFF stream) and [xmp] to [out] (not closed). */
    @Suppress("CyclomaticComplexMethod")
    fun write(image: CodedImage, colour: ColourInfo, exifTiff: ByteArray?, xmp: String?, out: OutputStream) {
        val tileIds = image.tiles.indices.map { FIRST_TILE_ID + it }
        val exifId = if (exifTiff != null) FIRST_TILE_ID + image.tiles.size else null
        val xmpId = if (xmp != null) FIRST_TILE_ID + image.tiles.size + (if (exifId != null) 1 else 0) else null
        val large = image.width > 0xFFFF || image.height > 0xFFFF
        val grid = ByteArrayOutputStream().apply {
            write(0) // version
            write(if (large) 1 else 0)
            write(image.rows - 1)
            write(image.columns - 1)
            if (large) {
                writeInt(image.width)
                writeInt(image.height)
            } else {
                writeShort(image.width)
                writeShort(image.height)
            }
        }.toByteArray()
        val exifData = exifTiff?.let {
            ByteArrayOutputStream().apply {
                writeInt(EXIF_HEADER.size)
                write(EXIF_HEADER)
                write(it)
            }.toByteArray()
        }
        val xmpData = xmp?.toByteArray(Charsets.UTF_8)
        // Items in mdat order with their data.
        val items = ArrayList<Pair<Int, ByteArray>>()
        items += GRID_ID to grid
        image.tiles.forEachIndexed { i, t -> items += tileIds[i] to t }
        if (exifId != null) items += exifId to exifData!!
        if (xmpId != null) items += xmpId to xmpData!!

        val ftyp = box("ftyp") {
            write(image.codec.brand.toByteArray())
            writeInt(0)
            for (brand in listOf("mif1", image.codec.brand, "miaf")) write(brand.toByteArray())
        }

        // The meta box's size does not depend on the offsets (fixed-size fields): build it once to measure.
        fun meta(dataStart: Long): ByteArray {
            val offsets = HashMap<Int, Long>()
            var at = dataStart
            for ((id, data) in items) {
                offsets[id] = at
                at += data.size
            }
            return fullBox("meta", 0, 0) {
                write(
                    fullBox("hdlr", 0, 0) {
                        writeInt(0)
                        write("pict".toByteArray())
                        repeat(3) { writeInt(0) }
                        write(0)
                    },
                )
                write(fullBox("pitm", 0, 0) { writeShort(GRID_ID) })
                write(iinf(image, tileIds, exifId, xmpId))
                write(iref(tileIds, exifId, xmpId))
                write(iprp(image, colour, tileIds))
                write(
                    fullBox("iloc", 0, 0) {
                        write(0x44) // offset_size 4, length_size 4
                        write(0x00) // base_offset_size 0, reserved
                        writeShort(items.size)
                        for ((id, data) in items) {
                            writeShort(id)
                            writeShort(0) // data_reference_index: this file
                            writeShort(1) // extent_count
                            writeInt(offsets.getValue(id).toInt())
                            writeInt(data.size)
                        }
                    },
                )
            }
        }
        val metaSize = meta(0).size
        val mdatHeader = 8
        val dataStart = ftyp.size.toLong() + metaSize + mdatHeader
        val mdatSize = mdatHeader + items.sumOf { it.second.size.toLong() }
        require(dataStart + mdatSize <= Int.MAX_VALUE) { "image too large for 32-bit offsets" }
        out.write(ftyp)
        out.write(meta(dataStart))
        out.writeInt(mdatSize.toInt())
        out.write("mdat".toByteArray())
        for ((_, data) in items) out.write(data)
        out.flush()
    }

    private fun iinf(image: CodedImage, tileIds: List<Int>, exifId: Int?, xmpId: Int?): ByteArray {
        val entries = ArrayList<ByteArray>()
        entries += infe(GRID_ID, "grid", hidden = false)
        tileIds.forEach { entries += infe(it, image.codec.itemType, hidden = true) }
        exifId?.let { entries += infe(it, "Exif", hidden = true) }
        xmpId?.let { entries += infe(it, "mime", hidden = true, contentType = "application/rdf+xml") }
        return fullBox("iinf", 0, 0) {
            writeShort(entries.size)
            entries.forEach { write(it) }
        }
    }

    private fun infe(id: Int, type: String, hidden: Boolean, contentType: String? = null): ByteArray =
        fullBox("infe", 2, if (hidden) 1 else 0) {
            writeShort(id)
            writeShort(0)
            write(type.toByteArray())
            write(0) // item_name ""
            if (contentType != null) {
                write(contentType.toByteArray())
                write(0)
            }
        }

    private fun iref(tileIds: List<Int>, exifId: Int?, xmpId: Int?): ByteArray = fullBox("iref", 0, 0) {
        write(
            box("dimg") {
                writeShort(GRID_ID)
                writeShort(tileIds.size)
                tileIds.forEach { writeShort(it) }
            },
        )
        for (from in listOfNotNull(exifId, xmpId)) {
            write(
                box("cdsc") {
                    writeShort(from)
                    writeShort(1)
                    writeShort(GRID_ID)
                },
            )
        }
    }

    private fun iprp(image: CodedImage, colour: ColourInfo, tileIds: List<Int>): ByteArray {
        // Property indices are 1-based in ipma.
        val properties = ArrayList<ByteArray>()
        fun add(b: ByteArray): Int {
            properties += b
            return properties.size
        }
        val config = add(box(image.codec.configBox) { write(image.config) })
        val tileSize = add(ispe(image.tileWidth, image.tileHeight))
        val fullSize = add(ispe(image.width, image.height))
        val pixi = add(
            fullBox("pixi", 0, 0) {
                write(3)
                repeat(3) { write(image.bitDepth) }
            },
        )
        val nclx = add(
            box("colr") {
                write("nclx".toByteArray())
                writeShort(colour.primaries)
                writeShort(colour.transfer)
                writeShort(colour.matrix)
                write(if (colour.fullRange) 0x80 else 0)
            },
        )
        val prof = colour.icc?.let { icc ->
            add(
                box("colr") {
                    write("prof".toByteArray())
                    write(icc)
                },
            )
        }
        val colours = listOfNotNull(nclx, prof)
        val associations = ArrayList<Pair<Int, List<Pair<Int, Boolean>>>>()
        associations += GRID_ID to (listOf(fullSize to false, pixi to false) + colours.map { it to true })
        tileIds.forEach {
            associations += it to (
                listOf(config to true, tileSize to false, pixi to false) + colours.map { c -> c to true }
                )
        }
        return box("iprp") {
            write(box("ipco") { properties.forEach { write(it) } })
            write(
                fullBox("ipma", 0, 0) {
                    writeInt(associations.size)
                    for ((id, props) in associations) {
                        writeShort(id)
                        write(props.size)
                        props.forEach { (index, essential) -> write((if (essential) 0x80 else 0) or index) }
                    }
                },
            )
        }
    }

    private fun ispe(w: Int, h: Int) = fullBox("ispe", 0, 0) {
        writeInt(w)
        writeInt(h)
    }

    private fun box(type: String, body: ByteArrayOutputStream.() -> Unit): ByteArray {
        val content = ByteArrayOutputStream().apply(body).toByteArray()
        return ByteArrayOutputStream().apply {
            writeInt(8 + content.size)
            write(type.toByteArray())
            write(content)
        }.toByteArray()
    }

    private fun fullBox(type: String, version: Int, flags: Int, body: ByteArrayOutputStream.() -> Unit): ByteArray =
        box(type) {
            writeInt((version shl 24) or flags)
            body()
        }

    private val EXIF_HEADER =
        byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
}
