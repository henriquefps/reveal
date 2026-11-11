@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.optics

import java.io.InputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/** What the EXIF says about the shot, for a lensfun lookup. */
data class ShotOptics(
    val make: String?,
    val model: String?,
    val lens: String?,
    /** Real focal length in mm. */
    val focalLength: Double?,
    val fNumber: Double?,
    /** 35 mm equivalent focal length (gives the crop factor of a camera lensfun does not know). */
    val focalLength35: Int? = null,
)

/**
 * The lensfun database as Reveal ships it (`scripts/lensfun/convert_lensfun.py`, CC BY-SA 3.0, docs/adr/0015): cameras
 * (maker, model, mount, crop factor) and lenses with their calibrations. [find] matches a shot to a lens and
 * interpolates the calibration to its focal length and aperture, like lensfun's own lookup in simplified form:
 *
 * - the camera by maker and model (case and punctuation insensitive), for its mount and crop factor; an unknown
 *   camera takes its crop from `focal35 / focal` (1 without it) and any mount;
 * - the lens by name: every number of the EXIF lens name (focal lengths, apertures) must appear in the candidate and
 *   the best share of matching words wins, among lenses for the camera's mount;
 * - terms interpolated linearly in focal length (clamped to the calibrated range); vignetting also in aperture, at the
 *   farthest focus distance the database has.
 */
class LensfunDatabase private constructor(private val cameras: List<Camera>, private val lenses: List<Lens>) {
    private class Camera(val maker: String, val model: String, val mount: String, val crop: Double)

    private class Lens(
        val maker: String,
        val names: List<String>,
        val mounts: List<String>,
        val crop: Double,
        val aspect: Double,
        val distortion: List<JsonArray>,
        val tca: List<JsonArray>,
        val vignetting: List<JsonArray>,
    )

    /** Cameras in the database. */
    val cameraCount: Int get() = cameras.size

    /** Lenses with a calibration in the database. */
    val lensCount: Int get() = lenses.size

    /** The profile of [shot], or `null` when no lens matches (or the EXIF names none). */
    @Suppress("ReturnCount")
    fun find(shot: ShotOptics): LensfunProfile? {
        val lensName = shot.lens?.takeIf { it.isNotBlank() } ?: return null
        val camera = camera(shot.make, shot.model)
        val focal = shot.focalLength
        val cropImage = camera?.crop ?: shot.focalLength35?.let { f35 ->
            focal?.takeIf { it > 0 }?.let { f35 / it }
        } ?: 1.0
        val lens = lens(lensName, camera?.mount) ?: return null
        val f = focal ?: lens.distortion.firstOrNull()?.get(0)?.jsonPrimitive?.double ?: return null
        val (dModel, dTerms) = distortion(lens.distortion, f)
        val (tModel, tTerms) = tca(lens.tca, f)
        val v = vignetting(lens.vignetting, f, shot.fNumber)
        return LensfunProfile(
            name = lens.names.first(),
            distortionModel = dModel,
            distortionTerms = dTerms,
            tcaModel = tModel,
            tcaTerms = tTerms,
            vignettingTerms = v,
            cropCalibration = lens.crop,
            aspectCalibration = lens.aspect,
            cropImage = cropImage,
        )
    }

    private fun camera(make: String?, model: String?): Camera? {
        if (make == null || model == null) return null
        val mk = normal(make)
        val md = normal(model)
        return cameras.firstOrNull { normal(it.model) == md && (mk.contains(normal(it.maker)) || normal(it.maker).contains(mk)) }
    }

    private fun lens(name: String, mount: String?): Lens? {
        val wanted = tokens(name)
        val numbers = wanted.filter { it.first().isDigit() }.toSet()
        var best: Lens? = null
        var bestScore = 0.0
        for (lens in lenses) {
            if (mount != null && mount !in lens.mounts) continue
            for (candidate in lens.names) {
                val have = tokens(candidate + " " + lens.maker)
                if (!have.containsAll(numbers)) continue
                val common = wanted.count { it in have }
                // Share of the EXIF words found, a small penalty for words the EXIF does not have.
                val score = common.toDouble() / wanted.size - EXTRA_WORD * (have.size - common)
                if (score > bestScore) {
                    bestScore = score
                    best = lens
                }
            }
        }
        return best?.takeIf { bestScore >= MIN_SCORE }
    }

    companion object {
        private const val EXTRA_WORD = 0.01
        private const val MIN_SCORE = 0.5
        private const val RESOURCE = "/lensfun/lensfun.json.gz"

        @Volatile
        private var shipped: LensfunDatabase? = null

        /** The database bundled with the app (loaded once, about 30 ms). */
        fun shipped(): LensfunDatabase = shipped ?: synchronized(this) {
            shipped ?: load(checkNotNull(LensfunDatabase::class.java.getResourceAsStream(RESOURCE)) {
                "lensfun asset missing"
            }).also { shipped = it }
        }

        /** Parses a gzipped asset written by `convert_lensfun.py`. */
        fun load(gzipped: InputStream): LensfunDatabase {
            val text = GZIPInputStream(gzipped).use { it.readBytes().decodeToString() }
            val root = Json.parseToJsonElement(text).jsonObject
            val cameras = root.getValue("cameras").jsonArray.map {
                val a = it.jsonArray
                Camera(a[0].jsonPrimitive.content, a[1].jsonPrimitive.content, a[2].jsonPrimitive.content, a[3].jsonPrimitive.double)
            }
            val lenses = root.getValue("lenses").jsonArray.map { e ->
                val o = e.jsonObject
                Lens(
                    maker = o["maker"]?.jsonPrimitive?.content.orEmpty(),
                    names = o.getValue("models").jsonArray.map { it.jsonPrimitive.content },
                    mounts = o["mounts"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                    crop = o.getValue("crop").jsonPrimitive.double,
                    aspect = o["aspect"]?.jsonPrimitive?.double ?: DEFAULT_ASPECT,
                    distortion = o.rows("d"),
                    tca = o.rows("t"),
                    vignetting = o.rows("v"),
                )
            }
            return LensfunDatabase(cameras, lenses)
        }

        private const val DEFAULT_ASPECT = 1.5

        private fun JsonObject.rows(key: String): List<JsonArray> = this[key]?.jsonArray?.map { it.jsonArray }.orEmpty()

        private fun normal(s: String): String = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

        /** Lower-case words and numbers of a lens name (`f/3.5-5.6` gives `f`, `3.5`, `5.6`; `16-50mm` gives `16`, `50`, `mm`). */
        internal fun tokens(s: String): Set<String> =
            Regex("[a-z]+|[0-9]+(?:\\.[0-9]+)?").findAll(s.lowercase(Locale.ROOT)).map { m ->
                m.value.removeSuffix(".0")
            }.toSet()

        private fun JsonArray.d(i: Int): Double = this[i].jsonPrimitive.double

        /** Rows (sorted by focal) interpolated linearly at [f], clamped; [from] is the first term's column. */
        private fun interpolate(rows: List<JsonArray>, f: Double, from: Int): List<Double> {
            val lo = rows.lastOrNull { it.d(0) <= f } ?: rows.first()
            val hi = rows.firstOrNull { it.d(0) >= f } ?: rows.last()
            val t = if (hi.d(0) == lo.d(0)) 0.0 else (f - lo.d(0)) / (hi.d(0) - lo.d(0))
            return (from until lo.size).map { i -> lo.d(i) + (hi.d(i) - lo.d(i)) * t }
        }

        private fun distortion(rows: List<JsonArray>, f: Double): Pair<LensfunDistortion, List<Double>> {
            if (rows.isEmpty()) return LensfunDistortion.NONE to emptyList()
            // One model per lens in practice; keep the rows of the model of the nearest calibration.
            val model = rows.minBy { abs(it.d(0) - f) }[1].jsonPrimitive.content
            val same = rows.filter { it[1].jsonPrimitive.content == model }
            val kind = when (model) {
                "ptlens" -> LensfunDistortion.PTLENS
                "poly3" -> LensfunDistortion.POLY3
                "poly5" -> LensfunDistortion.POLY5
                else -> return LensfunDistortion.NONE to emptyList()
            }
            return kind to interpolate(same, f, 2)
        }

        private fun tca(rows: List<JsonArray>, f: Double): Pair<LensfunTca, List<Double>> {
            if (rows.isEmpty()) return LensfunTca.NONE to emptyList()
            val model = rows.minBy { abs(it.d(0) - f) }[1].jsonPrimitive.content
            val same = rows.filter { it[1].jsonPrimitive.content == model }
            val kind = if (model == "linear") LensfunTca.LINEAR else LensfunTca.POLY3
            return kind to interpolate(same, f, 2)
        }

        /** `k1, k2, k3` at focal [f] and aperture [n] (the widest calibrated aperture when unknown). */
        private fun vignetting(rows: List<JsonArray>, f: Double, n: Double?): List<Double> {
            if (rows.isEmpty()) return emptyList()
            val focals = rows.map { it.d(0) }.distinct().sorted()
            fun at(focal: Double): List<Double> {
                val byAperture = rows.filter { it.d(0) == focal }.sortedBy { it.d(1) }
                val a = n ?: byAperture.first().d(1)
                return interpolateAperture(byAperture, a)
            }
            val lo = focals.lastOrNull { it <= f } ?: focals.first()
            val hi = focals.firstOrNull { it >= f } ?: focals.last()
            val vl = at(lo)
            val vh = at(hi)
            val t = if (hi == lo) 0.0 else (f - lo) / (hi - lo)
            return vl.indices.map { vl[it] + (vh[it] - vl[it]) * t }
        }

        private fun interpolateAperture(rows: List<JsonArray>, a: Double): List<Double> {
            val lo = rows.lastOrNull { it.d(1) <= a } ?: rows.first()
            val hi = rows.firstOrNull { it.d(1) >= a } ?: rows.last()
            val t = if (hi.d(1) == lo.d(1)) 0.0 else (a - lo.d(1)) / (hi.d(1) - lo.d(1))
            return (2 until 5).map { i -> lo.d(i) + (hi.d(i) - lo.d(i)) * t }
        }
    }
}
