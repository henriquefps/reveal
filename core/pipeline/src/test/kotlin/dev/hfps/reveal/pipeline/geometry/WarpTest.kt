package dev.hfps.reveal.pipeline.geometry

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.cpu.GeometryOp
import dev.hfps.reveal.pipeline.optics.SonyLensProfile
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.Optics
import dev.hfps.reveal.pipeline.recipe.Transform
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random
import org.junit.Test

class WarpTest {
    private val zv = checkNotNull(
        SonyLensProfile.of(
            "E PZ 16-50mm",
            listOf(11, 21, 2, -22, -49, -84, -124, -168, -208, -247, -279, -305),
            listOf(11, 0, 0, 128, 352, 736, 1184, 1920, 3200, 4768, 6528, 8384),
            listOf(
                22, -512, -512, -384, -384, -256, -256, -256, -128, 0, 128, 256,
                256, 768, 1024, 1280, 1408, 1408, 1408, 1280, 1024, 768, 512,
            ),
        ),
    )

    @Test
    fun `homography inverse and composition`() {
        val f = Perspective.forward(
            Transform(vertical = 30f, horizontal = -20f, rotate = 3f, aspect = 10f, scale = 90f),
        )
        val id = f * f.inverse()
        for (i in 0 until 9) assertThat(id.m[i]).isWithin(1e-12).of(Homography.IDENTITY.m[i])
        assertThat(Perspective.toCorrected(Transform()).isIdentity).isTrue()
    }

    @Test
    fun `vertical tilt grows the top and keeps the centre`() {
        val f = Perspective.forward(Transform(vertical = 50f))
        val centre = checkNotNull(f.map(0.0, 0.0))
        assertThat(centre.x).isWithin(1e-12).of(0.0)
        assertThat(centre.y).isWithin(1e-12).of(0.0)
        val topRight = checkNotNull(f.map(0.6, -0.4))
        val bottomRight = checkNotNull(f.map(0.6, 0.4))
        assertThat(topRight.x).isGreaterThan(bottomRight.x)
        val h = Perspective.forward(Transform(horizontal = 50f))
        assertThat(checkNotNull(h.map(0.6, -0.4)).y).isLessThan(checkNotNull(h.map(-0.6, -0.4)).y)
    }

    @Test
    fun `transform sliders map as documented`() {
        val rotate = Perspective.forward(Transform(rotate = 10f))
        val p = checkNotNull(rotate.map(1.0, 0.0))
        assertThat(Math.toDegrees(kotlin.math.atan2(p.y, p.x))).isWithin(1e-9).of(10.0)
        val scale = checkNotNull(Perspective.forward(Transform(scale = 150f)).map(0.2, 0.1))
        assertThat(scale.x).isWithin(1e-12).of(0.3)
        val offset = checkNotNull(Perspective.forward(Transform(offsetX = 100f, offsetY = -50f)).map(0.0, 0.0))
        assertThat(offset.x).isWithin(1e-12).of(Perspective.MAX_OFFSET)
        assertThat(offset.y).isWithin(1e-12).of(-Perspective.MAX_OFFSET / 2)
        val aspect = checkNotNull(Perspective.forward(Transform(aspect = 100f)).map(0.2, 0.2))
        assertThat(aspect.x / aspect.y).isWithin(1e-9).of(2.0)
    }

    @Test
    fun `geometry op reads each channel at its warped source point`() {
        val w = 120
        val h = 80
        // Pixel (x, y) holds (x, y, x): Catmull-Rom reproduces these ramps, so each output channel names its source.
        val rgb = FloatArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (y * w + x) * 3
                rgb[i] = x.toFloat()
                rgb[i + 1] = y.toFloat()
                rgb[i + 2] = x.toFloat()
            }
        }
        val g = Geometry(transform = Transform(vertical = 20f, rotate = 2f, scale = 110f))
        val mapping = GeometryMapping.of(g, w, h, zv, Optics(lensCorrections = true, removeCa = true))
        val warp = checkNotNull(mapping.warp)
        assertThat(warp.chromatic).isTrue()
        assertThat(mapping.axisAligned).isFalse()
        val out = GeometryOp.apply(rgb, mapping)
        val src = FloatArray(6)
        var checked = 0
        for (y in 10 until 70) {
            for (x in 10 until 110) {
                val ip = mapping.outputToImage(x + 0.5, y + 0.5)
                check(warp.sourcePoint(ip.x.toFloat(), ip.y.toFloat(), src))
                // Away from the clamped edge taps.
                if (src.any { it < 3f } || src[0] > w - 3 || src[2] > w - 3 || src[4] > w - 3 ||
                    src[3] > h - 3
                ) {
                    continue
                }
                val o = (y * w + x) * 3
                assertThat(out[o].toDouble()).isWithin(2e-3).of(src[0] - 0.5)
                assertThat(out[o + 1].toDouble()).isWithin(2e-3).of(src[3] - 0.5)
                assertThat(out[o + 2].toDouble()).isWithin(2e-3).of(src[4] - 0.5)
                checked++
            }
        }
        assertThat(checked).isGreaterThan(3000)
        // Red and blue really differ (CA) somewhere near the corners.
        val corner = mapping.outputToImage(5.5, 5.5)
        check(warp.sourcePoint(corner.x.toFloat(), corner.y.toFloat(), src))
        assertThat(abs(src[0] - src[4])).isGreaterThan(0.005f)
    }

    @Test
    fun `source regions cover every tap of a warped tile`() {
        val w = 300
        val h = 200
        val random = Random(7)
        repeat(40) {
            val g = Geometry(
                angle = random.nextFloat() * 10 - 5,
                crop = CropRect(0.1f, 0.1f, 0.9f, 0.85f),
                transform = Transform(
                    vertical = random.nextFloat() * 80 - 40,
                    horizontal = random.nextFloat() * 80 - 40,
                    scale = 80f + random.nextFloat() * 40,
                ),
            )
            val m = GeometryMapping.of(g, w, h, zv, Optics(lensCorrections = true, removeCa = true))
            val warp = checkNotNull(m.warp)
            val tx = random.nextInt(0, m.outputWidth - 40)
            val ty = random.nextInt(0, m.outputHeight - 30)
            val region = m.sourceRegion(tx, ty, 40, 30) ?: return@repeat
            val src = FloatArray(6)
            for (y in ty until ty + 30) {
                for (x in tx until tx + 40) {
                    val ip = m.outputToImage(x + 0.5, y + 0.5)
                    if (ip.x < 0 || ip.y < 0 || ip.x > w || ip.y > h) continue
                    if (!warp.sourcePoint(ip.x.toFloat(), ip.y.toFloat(), src)) continue
                    if (src[2] < 0 || src[3] < 0 || src[2] > w || src[3] > h) continue
                    for (c in 0 until 3) {
                        val bx = floor(src[c * 2] - 0.5f).toInt() - 1
                        val by = floor(src[c * 2 + 1] - 0.5f).toInt() - 1
                        // Taps bx..bx+3 clamped to the image must lie in the region.
                        assertThat(bx.coerceIn(0, w - 1)).isAtLeast(region.left)
                        assertThat((bx + 3).coerceIn(0, w - 1)).isLessThan(region.right)
                        assertThat(by.coerceIn(0, h - 1)).isAtLeast(region.top)
                        assertThat((by + 3).coerceIn(0, h - 1)).isLessThan(region.bottom)
                    }
                }
            }
        }
    }

    @Test
    fun `constrained transform keeps every crop corner inside the corrected image`() {
        val w = 600
        val h = 400
        val start = Geometry()
        for (t in listOf(
            Transform(vertical = 40f),
            Transform(horizontal = -35f, rotate = 4f),
            Transform(vertical = -25f, horizontal = 20f, aspect = 30f, scale = 95f, offsetX = 10f),
        )) {
            val g = CropEditing.transform(start, t, w, h)
            val frame = CropEditing.frame(g, w, h)
            assertThat(CropEditing.isValid(g, frame, w, h)).isTrue()
            // The frame is as large as it can be: 1 % larger about its centre is invalid.
            val bigger = Box.centred(frame.centerX, frame.centerY, frame.width * 1.01, frame.height * 1.01)
            assertThat(CropEditing.isValid(g, bigger, w, h, 0.0)).isFalse()
            // Rendering it shows no empty pixel.
            val m = GeometryMapping.of(g, w, h)
            val warp = checkNotNull(m.warp)
            val src = FloatArray(6)
            for (k in 0..50) {
                for ((ox, oy) in listOf(
                    m.outputWidth * k / 50.0 to 0.5,
                    m.outputWidth * k / 50.0 to m.outputHeight - 0.5,
                    0.5 to m.outputHeight * k / 50.0,
                    m.outputWidth - 0.5 to m.outputHeight * k / 50.0,
                )) {
                    val ip = m.outputToImage(ox, oy)
                    check(warp.sourcePoint(ip.x.toFloat(), ip.y.toFloat(), src))
                    assertThat(src[2]).isAtLeast(-0.5f)
                    assertThat(src[3]).isAtLeast(-0.5f)
                    assertThat(src[2]).isAtMost(w + 0.5f)
                    assertThat(src[3]).isAtMost(h + 0.5f)
                }
            }
        }
    }

    @Test
    fun `identity transform and no lens leave the mapping unchanged`() {
        val m = GeometryMapping.of(Geometry(), 100, 60, zv, Optics())
        assertThat(m.warp).isNull()
        assertThat(m.isIdentity).isTrue()
        assertThat(
            GeometryMapping.of(
                Geometry(transform = Transform(upright = dev.hfps.reveal.pipeline.recipe.UprightMode.AUTO)),
                100,
                60,
            ).warp,
        ).isNull()
    }
}
