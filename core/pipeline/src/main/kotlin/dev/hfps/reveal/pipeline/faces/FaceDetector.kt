@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.faces

import java.util.stream.IntStream
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * A detected face in normalised image coordinates (0..1 of the width / height, origin top-left). [left]..[bottom] is
 * the detector's box (roughly brows to chin, ear to ear); [keypoints] are six (x, y) pairs: right eye, left eye, nose
 * tip, mouth centre, right ear tragion, left ear tragion (the subject's right/left).
 */
data class Face(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val score: Float,
    val keypoints: List<Float> = emptyList(),
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centreX: Float get() = (left + right) / 2
    val centreY: Float get() = (top + bottom) / 2

    /** Intersection over union with [other] (in normalised units, so only for faces of the same image). */
    fun iou(other: Face): Float {
        val w = min(right, other.right) - max(left, other.left)
        val h = min(bottom, other.bottom) - max(top, other.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        return inter / (width * height + other.width * other.height - inter)
    }
}

/**
 * An sRGB-encoded image for the detector: interleaved R, G, B in 0..1, row-major from the top-left
 * ([FaceInput.from] makes one from a pipeline input).
 */
class DetectionImage(val width: Int, val height: Int, val rgb: FloatArray) {
    init {
        require(rgb.size == width * height * 3) { "rgb must have width*height*3 values" }
    }
}

/**
 * Face detection (docs/adr/0010, "Faces"): MediaPipe's BlazeFace full-range model (Apache-2.0), run in pure Kotlin
 * by [NetworkGraph], offline, deterministic, the same on the JVM and on every device.
 *
 * The model sees 192 x 192 pixels and finds faces down to about 10 of them, so the image is searched twice:
 * 1. **Whole frame**, letterboxed into 192 x 192 (large faces: portraits, close-ups).
 * 2. **Tiles** of 192 x 192 over the image at [DETECT_SIDE] px on its long side, overlapping by [TILE_OVERLAP] px
 *    (small faces: groups, people at a distance). A tile keeps only faces smaller than [TILE_MAX_FACE] of its side
 *    whose centre is not in an overlap margin it shares with a neighbour (the neighbour sees those whole).
 *
 * Each pass decodes the 2304 anchors (48 x 48 grid, stride 4, fixed size: box centre and size, six keypoints, a
 * sigmoid score; MediaPipe's `face_detection_full_range` options), keeps scores >= [MIN_SCORE], and both passes are
 * merged by MediaPipe's weighted non-maximum suppression (IoU [NMS_IOU]: a cluster's box and keypoints are the
 * score-weighted mean, its score the best one). The tiles run in parallel; each result lands in its own slot, so the
 * outcome does not depend on scheduling.
 */
class FaceDetector(private val graph: NetworkGraph) {
    init {
        require(graph.inputShape.contentEquals(intArrayOf(1, SIZE, SIZE, 3))) { "unexpected detector input" }
    }

    /** The faces in [image], best score first. */
    fun detect(image: DetectionImage): List<Face> {
        val windows = windows(image)
        val found = arrayOfNulls<List<Face>>(windows.size)
        IntStream.range(0, windows.size).parallel().forEach { i -> found[i] = runWindow(image, windows[i]) }
        return suppress(found.flatMap { it.orEmpty() })
    }

    /**
     * A square window of the image fed to the network: source rectangle ([x0], [y0], [size] x [size] source pixels,
     * which may extend past the image: padding) and whether it is a tile ([tile]) of the second pass.
     */
    private class Window(val x0: Float, val y0: Float, val size: Float, val tile: Boolean, val margins: BooleanArray)

    private fun windows(image: DetectionImage): List<Window> {
        val long = max(image.width, image.height).toFloat()
        val whole = Window(
            (image.width - long) / 2,
            (image.height - long) / 2,
            long,
            tile = false,
            margins = BooleanArray(4),
        )
        val scale = long / DETECT_SIDE
        if (long <= SIZE * scale * 1.5f) return listOf(whole)
        val tile = SIZE * scale
        val stride = (SIZE - TILE_OVERLAP) * scale
        fun starts(extent: Int): List<Float> {
            if (extent <= tile) return listOf((extent - tile) / 2)
            val out = ArrayList<Float>()
            var s = 0f
            while (s + tile < extent) {
                out += s
                s += stride
            }
            out += extent - tile
            return out
        }
        val xs = starts(image.width)
        val ys = starts(image.height)
        val tiles = ys.flatMapIndexed { j, y ->
            xs.mapIndexed { i, x ->
                // left, top, right, bottom: true where a neighbour shares the edge.
                Window(x, y, tile, tile = true, margins = booleanArrayOf(i > 0, j > 0, i < xs.lastIndex, j < ys.lastIndex))
            }
        }
        return listOf(whole) + tiles
    }

    private fun runWindow(image: DetectionImage, w: Window): List<Face> {
        val input = resample(image, w)
        val out = graph.run(input)
        val regressors = out[0]
        val scores = out[1]
        val faces = ArrayList<Face>()
        for (a in 0 until ANCHORS) {
            val logit = scores[a].coerceIn(-SCORE_CLIP, SCORE_CLIP)
            val score = (1.0 / (1.0 + exp(-logit.toDouble()))).toFloat()
            if (score < MIN_SCORE) continue
            val ax = (a % GRID + 0.5f) / GRID
            val ay = (a / GRID + 0.5f) / GRID
            val r = a * COORDS
            val cx = regressors[r] / SIZE + ax
            val cy = regressors[r + 1] / SIZE + ay
            val bw = regressors[r + 2] / SIZE
            val bh = regressors[r + 3] / SIZE
            if (w.tile && !keepInTile(cx, cy, bw, bh, w.margins)) continue
            fun x(u: Float) = (w.x0 + u * w.size) / image.width
            fun y(v: Float) = (w.y0 + v * w.size) / image.height
            val keypoints = (0 until KEYPOINTS).flatMap { k ->
                listOf(x(regressors[r + 4 + 2 * k] / SIZE + ax), y(regressors[r + 5 + 2 * k] / SIZE + ay))
            }
            faces += Face(x(cx - bw / 2), y(cy - bh / 2), x(cx + bw / 2), y(cy + bh / 2), score, keypoints)
        }
        return faces
    }

    /** A tile keeps small faces whose centre is outside the margins it shares with a neighbour. */
    private fun keepInTile(cx: Float, cy: Float, bw: Float, bh: Float, margins: BooleanArray): Boolean {
        if (max(bw, bh) > TILE_MAX_FACE) return false
        val m = TILE_OVERLAP / 2f / SIZE
        return !(margins[0] && cx < m) && !(margins[1] && cy < m) &&
            !(margins[2] && cx > 1 - m) && !(margins[3] && cy > 1 - m)
    }

    /**
     * The window as the network's input: area-averaged (box filter over the source pixels each input pixel covers)
     * into 192 x 192, mapped to -1..1; outside the image -1 (black), as MediaPipe's letterbox.
     */
    private fun resample(image: DetectionImage, w: Window): FloatArray {
        val out = FloatArray(SIZE * SIZE * 3) { -1f }
        val step = w.size / SIZE
        for (y in 0 until SIZE) {
            val sy0 = w.y0 + y * step
            val sy1 = sy0 + step
            val ya = max(0, kotlin.math.floor(sy0).toInt())
            val yb = min(image.height, kotlin.math.ceil(sy1).toInt())
            if (ya >= yb) continue
            for (x in 0 until SIZE) {
                val sx0 = w.x0 + x * step
                val sx1 = sx0 + step
                val xa = max(0, kotlin.math.floor(sx0).toInt())
                val xb = min(image.width, kotlin.math.ceil(sx1).toInt())
                if (xa >= xb) continue
                var r = 0f
                var g = 0f
                var b = 0f
                var wsum = 0f
                for (yy in ya until yb) {
                    val wy = min(sy1, yy + 1f) - max(sy0, yy.toFloat())
                    if (wy <= 0f) continue
                    for (xx in xa until xb) {
                        val wx = min(sx1, xx + 1f) - max(sx0, xx.toFloat())
                        if (wx <= 0f) continue
                        val k = wx * wy
                        val i = (yy * image.width + xx) * 3
                        r += image.rgb[i] * k
                        g += image.rgb[i + 1] * k
                        b += image.rgb[i + 2] * k
                        wsum += k
                    }
                }
                // Partly outside the image: the outside part is black, as the letterbox.
                val area = step * step
                val o = (y * SIZE + x) * 3
                out[o] = (r / area) * 2 - 1
                out[o + 1] = (g / area) * 2 - 1
                out[o + 2] = (b / area) * 2 - 1
                if (wsum <= 0f) out.fill(-1f, o, o + 3)
            }
        }
        return out
    }

    companion object {
        /** The network's input side. */
        const val SIZE: Int = 192

        /** Long side of the image the tiles cover (faces down to about 3 % of the long side). */
        const val DETECT_SIDE: Int = 576

        /** Overlap of neighbouring tiles, in network pixels. */
        const val TILE_OVERLAP: Int = 48

        /** Largest face a tile reports, as a fraction of its side (larger ones are the whole-frame pass's). */
        const val TILE_MAX_FACE: Float = 0.4f

        /** Lowest score kept (MediaPipe's default for this model). */
        const val MIN_SCORE: Float = 0.6f

        /** IoU above which two detections are the same face. */
        const val NMS_IOU: Float = 0.3f

        private const val GRID = 48
        private const val ANCHORS = GRID * GRID
        private const val COORDS = 16
        private const val KEYPOINTS = 6
        private const val SCORE_CLIP = 100f
        private const val RESOURCE = "/faces/blazeface-full-range.rvnn.gz"

        @Volatile
        private var shipped: FaceDetector? = null

        /** The detector with the shipped network (loaded once, about 1 MB). */
        fun shipped(): FaceDetector = shipped ?: synchronized(this) {
            shipped ?: FaceDetector(
                checkNotNull(FaceDetector::class.java.getResourceAsStream(RESOURCE)) { "face model missing" }
                    .use(NetworkGraph::read),
            ).also { shipped = it }
        }

        /**
         * MediaPipe's weighted non-maximum suppression: the best remaining detection and every other one overlapping
         * it by more than [NMS_IOU] form a cluster; its box and keypoints are their score-weighted mean, its score the
         * best one. Best first; ties keep the input order (deterministic).
         */
        fun suppress(detections: List<Face>): List<Face> {
            val remaining = detections.sortedByDescending { it.score }.toMutableList()
            val out = ArrayList<Face>()
            while (remaining.isNotEmpty()) {
                val best = remaining.first()
                val cluster = remaining.filter { it === best || best.iou(it) > NMS_IOU }
                remaining.removeAll { c -> cluster.any { it === c } }
                val total = cluster.sumOf { it.score.toDouble() }.toFloat()
                fun mean(f: (Face) -> Float) = cluster.sumOf { (f(it) * it.score).toDouble() }.toFloat() / total
                val keypoints = best.keypoints.indices.map { k -> mean { it.keypoints.getOrElse(k) { 0f } } }
                out += Face(mean { it.left }, mean { it.top }, mean { it.right }, mean { it.bottom }, best.score, keypoints)
            }
            return out
        }
    }
}
