// Bit depths (8, 10) and P010 layout are the formats' own.
@file:Suppress("MagicNumber")

package dev.hfps.reveal.feature.export.encode

import android.annotation.SuppressLint
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import dev.hfps.image.write.Av1Config
import dev.hfps.image.write.CodedImage
import dev.hfps.image.write.HevcConfig
import dev.hfps.image.write.ImageCodec
import dev.hfps.image.write.RgbRows
import dev.hfps.image.write.YuvTile
import dev.hfps.image.write.YuvTiles
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Encodes an image as a grid of [TILE] x [TILE] tiles with the platform's HEVC or AV1 encoder (docs/adr/0009 "HEIF and
 * AVIF"): each tile is one intra frame of Y'CbCr 4:2:0 that Reveal converts itself ([YuvTiles]: BT.709 full range, 8
 * or 10 bits, P010 for 10), so the container's `colr` describes the samples exactly. Quality is the encoder's
 * constant-quality mode when it has one, else a variable bitrate per tile from the quality. Blocking.
 */
// COLOR_FormatYUVP010 is a constant (inlined): find() only picks encoders that list it.
@SuppressLint("InlinedApi")
class TileCodecEncoder(private val codec: ImageCodec, private val depth: Int) {
    /** Encodes [rows] at [quality] (1..100); [cancel] is polled between tiles. */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    fun encode(rows: RgbRows, quality: Int, cancel: () -> Boolean = { false }): CodedImage {
        val info = find(codec, depth) ?: throw IOException("no ${codec.name} encoder for $depth bits")
        val columns = (rows.width + TILE - 1) / TILE
        val gridRows = (rows.height + TILE - 1) / TILE
        val count = columns * gridRows
        val encoder = MediaCodec.createByCodecName(info.name)
        val started = System.nanoTime()
        try {
            encoder.configure(format(info, quality), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val tile = YuvTile(TILE, TILE, depth)
            val samples = ArrayList<ByteArray>(count)
            var config: ByteArray? = null
            val bufferInfo = MediaCodec.BufferInfo()
            var queued = 0
            var eosQueued = false
            var done = false
            while (!done) {
                if (!eosQueued) {
                    val index = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        if (queued < count) {
                            if (cancel()) throw CancellationException("export cancelled")
                            YuvTiles.convert(rows, (queued % columns) * TILE, (queued / columns) * TILE, tile)
                            val size = fill(encoder, index, tile)
                            encoder.queueInputBuffer(index, 0, size, queued * FRAME_US, 0)
                            queued++
                        } else {
                            val eos = MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            encoder.queueInputBuffer(index, 0, 0, queued * FRAME_US, eos)
                            eosQueued = true
                        }
                    }
                }
                val out = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (config == null) config = csd(encoder.outputFormat)
                    }

                    out >= 0 -> {
                        val buffer = checkNotNull(encoder.getOutputBuffer(out))
                        val bytes = ByteArray(bufferInfo.size)
                        buffer.position(bufferInfo.offset)
                        buffer.get(bytes)
                        encoder.releaseOutputBuffer(out, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            config = (config ?: ByteArray(0)) + bytes
                        } else if (bytes.isNotEmpty()) {
                            samples += bytes
                        }
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    }
                }
            }
            check(samples.size == count) { "encoder returned ${samples.size} samples for $count tiles" }
            val (record, tiles) = container(config, samples)
            val ms = (System.nanoTime() - started) / NANOS_PER_MS
            val bytes = tiles.sumOf { it.size }
            Log.i(TAG, "${info.name}: $count tiles of $TILE at $depth bits, quality $quality, $bytes bytes in $ms ms")
            return CodedImage(codec, record, rows.width, rows.height, TILE, TILE, columns, gridRows, depth, tiles)
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
        }
    }

    /** The codec configuration record and the tiles as container samples. */
    private fun container(config: ByteArray?, samples: List<ByteArray>): Pair<ByteArray, List<ByteArray>> =
        when (codec) {
            ImageCodec.HEVC -> {
                // Parameter sets from the config buffer, else from the first tile.
                val sets = config?.takeIf { it.isNotEmpty() } ?: samples.first()
                HevcConfig.hvcC(sets) to samples.map(HevcConfig::sample)
            }

            ImageCodec.AV1 -> {
                val record = config?.takeIf { it.isNotEmpty() }?.let { runCatching { Av1Config.av1C(it) }.getOrNull() }
                    ?: Av1Config.av1C(samples.first())
                record to samples.map(Av1Config::sample)
            }
        }

    private fun csd(format: MediaFormat): ByteArray? {
        val out = ByteArrayOutputStream()
        for (key in listOf("csd-0", "csd-1", "csd-2")) {
            val b = format.getByteBuffer(key) ?: continue
            val bytes = ByteArray(b.remaining())
            b.duplicate().get(bytes)
            out.write(bytes)
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun format(info: MediaCodecInfo, quality: Int): MediaFormat {
        val caps = info.getCapabilitiesForType(codec.mime)
        val format = MediaFormat.createVideoFormat(codec.mime, TILE, TILE)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            if (depth == 10) CodecCapabilities.COLOR_FormatYUVP010 else CodecCapabilities.COLOR_FormatYUV420Flexible,
        )
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 1)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
        format.setInteger(MediaFormat.KEY_PROFILE, profile(codec, depth))
        format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
        format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
        format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        val encoderCaps = checkNotNull(caps.encoderCapabilities)
        if (encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)) {
            val range = encoderCaps.qualityRange
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
            format.setInteger(
                MediaFormat.KEY_QUALITY,
                (range.lower + (range.upper - range.lower) * quality / MAX_QUALITY.toDouble()).roundToInt(),
            )
        } else {
            // One frame per second: the bitrate is the bits per tile.
            val bpp = MIN_BPP + (MAX_BPP - MIN_BPP) * (quality / MAX_QUALITY.toDouble()).pow(BPP_CURVE)
            val bitrate = (bpp * TILE * TILE).roundToInt()
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            val range = checkNotNull(caps.videoCapabilities).bitrateRange
            format.setInteger(MediaFormat.KEY_BIT_RATE, range.clamp(bitrate))
        }
        return format
    }

    /** Copies [tile] into input buffer [index] (an [Image] when the codec gives one); returns the byte count. */
    private fun fill(encoder: MediaCodec, index: Int, tile: YuvTile): Int {
        val image = encoder.getInputImage(index)
        if (image != null) {
            write(image, tile)
            return checkNotNull(encoder.getInputBuffer(index)).capacity()
        }
        // No Image: NV12 (8 bits) or P010 (10 bits), stride = width.
        val buffer = checkNotNull(encoder.getInputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
        val bytes = if (depth == 10) 2 else 1
        var at = 0
        for (v in tile.y) {
            putSample(buffer, at, v.toInt(), bytes)
            at += bytes
        }
        for (i in tile.u.indices) {
            putSample(buffer, at, tile.u[i].toInt(), bytes)
            putSample(buffer, at + bytes, tile.v[i].toInt(), bytes)
            at += 2 * bytes
        }
        return at
    }

    private fun write(image: Image, tile: YuvTile) {
        val bytes = if (depth == 10) 2 else 1
        val planes = image.planes
        fun plane(p: Image.Plane, samples: ShortArray, w: Int, h: Int) {
            val b = p.buffer.order(ByteOrder.LITTLE_ENDIAN)
            for (y in 0 until h) {
                var at = y * p.rowStride
                for (x in 0 until w) {
                    putSample(b, at, samples[y * w + x].toInt(), bytes)
                    at += p.pixelStride
                }
            }
        }
        plane(planes[0], tile.y, tile.width, tile.height)
        plane(planes[1], tile.u, tile.width / 2, tile.height / 2)
        plane(planes[2], tile.v, tile.width / 2, tile.height / 2)
    }

    /** One sample: a byte, or a little-endian 16-bit word with the 10 bits at the top (P010). */
    private fun putSample(b: ByteBuffer, at: Int, v: Int, bytes: Int) {
        if (bytes == 1) b.put(at, v.toByte()) else b.putShort(at, (v shl P010_SHIFT).toShort())
    }

    companion object {
        /** Tile edge: HEIF's usual 512 (the HEVC tile encoders' limit), AV1 the same for a uniform grid. */
        const val TILE = 512
        private const val TAG = "RevealExport"
        private const val TIMEOUT_US = 10_000L
        private const val FRAME_US = 1_000_000L
        private const val NANOS_PER_MS = 1_000_000L
        private const val MAX_QUALITY = 100
        private const val MIN_BPP = 0.3
        private const val MAX_BPP = 6.0
        private const val BPP_CURVE = 3.0
        private const val P010_SHIFT = 6

        val ImageCodec.mime: String
            get() = when (this) {
                ImageCodec.HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
                ImageCodec.AV1 -> MediaFormat.MIMETYPE_VIDEO_AV1
            }

        private fun profile(codec: ImageCodec, depth: Int): Int = when (codec) {
            ImageCodec.HEVC ->
                if (depth == 10) CodecProfileLevel.HEVCProfileMain10 else CodecProfileLevel.HEVCProfileMain

            ImageCodec.AV1 -> if (depth == 10) CodecProfileLevel.AV1ProfileMain10 else CodecProfileLevel.AV1ProfileMain8
        }

        /**
         * The encoder for [codec] at [depth] bits: it takes [TILE]-sized frames, the profile ([depth] 10 = Main10) and
         * the input format (P010 for 10 bits, YUV420Flexible for 8); hardware first. Null when the device has none.
         */
        fun find(codec: ImageCodec, depth: Int): MediaCodecInfo? {
            val wanted =
                if (depth == 10) CodecCapabilities.COLOR_FormatYUVP010 else CodecCapabilities.COLOR_FormatYUV420Flexible
            val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(codec.mime, ignoreCase = true) } &&
                    runCatching {
                        val caps = info.getCapabilitiesForType(codec.mime)
                        caps.videoCapabilities?.isSizeSupported(TILE, TILE) == true &&
                            wanted in caps.colorFormats &&
                            caps.profileLevels.any { it.profile == profile(codec, depth) }
                    }.getOrDefault(false)
            }

            // Constant quality first (the quality slider means what it says), then hardware.
            fun cq(info: MediaCodecInfo) = info.getCapabilitiesForType(codec.mime).encoderCapabilities
                ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ) == true
            return candidates.sortedWith(compareBy({ !cq(it) }, { !it.isHardwareAccelerated })).firstOrNull()
        }
    }
}
