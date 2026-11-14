@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.heal

import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.synthetic.HealScenes
import java.util.concurrent.Executors

/**
 * Runs the healing code once on a small synthetic scene (docs/adr/0023 "FIX/H"): a heal spot and a patch Remove stroke,
 * so the runtime has compiled the engine, the membrane, the crop read and the patch search before the first real spot
 * (on the tablet the first Remove of a session took about 4x a later one, the code still interpreted). Its own fills,
 * a few tens of milliseconds, on the calling thread (a worker).
 */
object HealWarmUp {
    private const val W = 320
    private const val H = 240

    fun run() {
        val scene = HealScenes.withObject(HealScenes.texturedGradient(W, H), 200, 120, 40)
        val spots = listOf(
            HealScenes.spot("warm-heal", Heal.KIND_HEAL, W, H, 80f, 80f, 12f, ox = 36f),
            HealScenes.stroke(
                "warm-remove",
                Heal.KIND_REMOVE,
                W,
                H,
                listOf(180f to 120f, 200f to 122f, 220f to 120f),
                14f,
            ),
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            HealOp.apply(scene, spots, HealContext(RemoveFills(executor = executor)))
        } finally {
            executor.shutdownNow()
        }
    }
}
