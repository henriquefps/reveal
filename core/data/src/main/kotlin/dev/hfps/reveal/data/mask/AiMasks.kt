package dev.hfps.reveal.data.mask

import android.util.Log
import dev.hfps.reveal.ml.MaskKind
import dev.hfps.reveal.ml.MaskPrompt
import dev.hfps.reveal.ml.MlImage
import dev.hfps.reveal.ml.refine.RefineDefaults
import dev.hfps.reveal.ml.service.MaskLookup
import dev.hfps.reveal.ml.service.MaskOutput
import dev.hfps.reveal.ml.service.MaskRequest
import dev.hfps.reveal.ml.service.MaskService
import dev.hfps.reveal.pipeline.mask.AiMask
import dev.hfps.reveal.pipeline.mask.AiMaskRequest
import dev.hfps.reveal.pipeline.mask.AiMaskSource
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where an AI mask component is (the editor shows it on the mask). */
enum class AiMaskState {
    /** The model runs (the first run also compiles it: about a second on the GPU). */
    COMPUTING,

    READY,

    /** The model failed (out of memory, a runtime error): the mask is kept, it renders as if the part were absent. */
    FAILED,

    /** The recipe names a kind or a model this app does not have (a newer app's, a removed model): kept, skipped. */
    UNAVAILABLE,
}

/**
 * The editor's and the export's AI masks (docs/adr/0022 "M4/B (editor)", docs/adr/0021 "M4/C"): adapts [MaskService] to
 * the engine's [AiMaskSource]. One [Session] per photo: it knows the photo's key and how to get its model input (the
 * upright, recipe-independent render of [MaskInputs], at most 1024 px), runs each component's request once in the
 * background, keeps the masks in memory and tells the renderer when one is ready.
 */
@Singleton
class AiMasks @Inject constructor(private val service: MaskService) {
    /**
     * A source for one photo; [input] renders its model input when a mask is first needed. [photoKey] is the masks
     * cache's key for the photo; `null` = the model input's content key ([MaskInputs.contentKey]).
     */
    fun session(photoKey: String? = null, input: suspend () -> MlImage?): Session =
        Session(service, photoKey, null, input)

    /**
     * A source that looks in the disk cache before it renders anything: with [photoKey] (the file's fingerprint,
     * [MaskInputs.photoKey]) and the model input's [size] ([MaskInputs.size]) the cache key is known without pixels,
     * so [input] runs only for a mask nobody made yet. The editor, the export and the thumbnails all open sessions
     * this way, so they share each other's masks (docs/adr/0022 "M4/F").
     */
    fun session(photoKey: String, size: Pair<Int, Int>, input: suspend () -> MlImage?): Session =
        Session(service, photoKey, size, input)

    /** Frees the models (the editor closed; they load again on the next request). */
    suspend fun release() = service.release()

    /**
     * One photo's AI masks. [mask] is called on a renderer's GL thread every frame: it is a map lookup; a miss starts
     * the computation (once per component) and returns null, and every `onReady` given meanwhile is called when the
     * mask arrives. [states] says where each component is ([key]).
     */
    class Session internal constructor(
        private val service: MaskService,
        @Volatile private var photoKey: String?,
        private val size: Pair<Int, Int>?,
        private val input: suspend () -> MlImage?,
    ) : AiMaskSource {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val masks = Collections.synchronizedMap(
            object : LinkedHashMap<String, AiMask>(MAX_KEPT, LOAD, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AiMask>?) = size > MAX_KEPT
            },
        )
        private val waiting = ConcurrentHashMap<String, MutableList<() -> Unit>>()
        private val jobs = ConcurrentHashMap<String, Job>()

        @Volatile
        private var image: MlImage? = null
        private val _states = MutableStateFlow<Map<String, AiMaskState>>(emptyMap())
        val states: StateFlow<Map<String, AiMaskState>> = _states.asStateFlow()

        override fun mask(request: AiMaskRequest, onReady: () -> Unit): AiMask? {
            val key = key(request.component)
            masks[key]?.let { return it }
            waiting.getOrPut(key) { Collections.synchronizedList(mutableListOf()) }.add(onReady)
            start(request.component, key)
            return null
        }

        /** Starts [component]'s mask now (a new AI mask: before any slider makes the renderer ask for it). */
        fun request(component: MaskComponent.Ai) {
            val key = key(component)
            if (masks[key] == null) start(component, key)
        }

        /** Computes the failed components again. */
        fun retry() {
            val failed = _states.value.filterValues { it == AiMaskState.FAILED }.keys
            _states.update { it - failed }
        }

        private fun start(component: MaskComponent.Ai, key: String): Unit = synchronized(jobs) {
            val state = _states.value[key]
            if (state == AiMaskState.FAILED || state == AiMaskState.UNAVAILABLE) return
            // A ready mask the memory cache dropped is made again (from the disk cache: fast).
            if (jobs[key]?.isActive == true) return
            val kind = kindOf(component)
            if (kind == null) {
                setState(key, AiMaskState.UNAVAILABLE)
                return
            }
            jobs[key] = scope.launch { compute(component, kind, key) }
        }

        private suspend fun compute(component: MaskComponent.Ai, kind: MaskKind, key: String) {
            setState(key, AiMaskState.COMPUTING)
            try {
                val out = cached(component, kind) ?: run {
                    val image = image ?: input()?.also { image = it } ?: error("no model input")
                    val key0 = photoKey ?: MaskInputs.contentKey(image).also { photoKey = it }
                    service.mask(request(component, kind, image, key0))
                }
                masks[key] = AiMask(out.mask.width, out.mask.height, out.mask.values)
                setState(key, AiMaskState.READY)
                val took = if (out.fromCache) "cached" else "${out.modelMs.roundToInt()} ms"
                log { Log.i(TAG, "${out.model} $kind $took") }
                notifyWaiting(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                // A model that does not exist here, or cannot make the kind (MaskService.resolve).
                log { Log.w(TAG, "AI mask unavailable: ${e.message}") }
                setState(key, AiMaskState.UNAVAILABLE)
                notifyWaiting(key)
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                // Includes OutOfMemoryError and the runtime's errors: the mask stays, the editor says so.
                log { Log.w(TAG, "AI mask failed", e) }
                setState(key, AiMaskState.FAILED)
                notifyWaiting(key)
            }
        }

        /** [component]'s mask from the disk cache when the key is known without pixels, else null. */
        private suspend fun cached(component: MaskComponent.Ai, kind: MaskKind): MaskOutput? {
            val key = photoKey
            val dims = size
            return if (key == null || dims == null) {
                null
            } else {
                service.cached(lookup(component, kind, key, dims.first, dims.second))
            }
        }

        /**
         * Starts every one of [components] and suspends until each is READY, FAILED or UNAVAILABLE; true when all are
         * READY (their masks are then in memory: [mask] answers at once).
         */
        suspend fun ready(components: Collection<MaskComponent.Ai>): Boolean {
            val keys = components.associateBy { key(it) }
            keys.values.forEach(::request)
            val done = states.first { s -> keys.keys.all { s[it] != null && s[it] != AiMaskState.COMPUTING } }
            return keys.keys.all { done[it] == AiMaskState.READY }
        }

        /** A waiting export renders without the part instead of waiting out its time limit. */
        private fun notifyWaiting(key: String) {
            waiting.remove(key)?.let { callbacks -> synchronized(callbacks) { callbacks.toList() } }?.forEach { it() }
        }

        private fun setState(key: String, state: AiMaskState) = _states.update { it + (key to state) }

        /** Stops the computations and drops the masks. */
        fun close() {
            scope.cancel()
            masks.clear()
            waiting.clear()
        }
    }

    companion object {
        private const val TAG = "RevealAiMasks"
        private const val MAX_KEPT = 16
        private const val LOAD = 0.75f

        /** The component's identity for the masks it makes (model, kind, prompts, seeds, refinement). */
        fun key(c: MaskComponent.Ai): String = buildString {
            append(c.model).append('|').append(c.kind).append('|').append(c.autoSeeds)
            c.prompts.forEach { append(String.format(Locale.ROOT, ";%.4f,%.4f", it.x, it.y)) }
            c.refine?.let { append(String.format(Locale.ROOT, "|r%.2f,%.6f", it.radius, it.eps)) }
        }

        /** The model kind a component names, `null` when this app does not know it. */
        fun kindOf(c: MaskComponent.Ai): MaskKind? = MaskKind.entries.firstOrNull { it.name == c.kind }

        /** The cache lookup of [component] for a model input of [width] x [height] (no pixels needed). */
        fun lookup(component: MaskComponent.Ai, kind: MaskKind, photoKey: String, width: Int, height: Int): MaskLookup =
            MaskLookup(
                photoKey = photoKey,
                width = width,
                height = height,
                kind = kind,
                inputVersion = MaskInputs.VERSION,
                model = component.model.ifEmpty { null },
                prompts = component.prompts.map { MaskPrompt(it.x, it.y) },
                autoSeeds = component.autoSeeds || component.prompts.isEmpty(),
                refine = component.refine?.let {
                    RefineDefaults.Setting(it.radius.roundToInt().coerceAtLeast(1), it.eps.coerceAtLeast(MIN_EPS))
                },
            )

        /** The service request of [component] on [image]. */
        fun request(component: MaskComponent.Ai, kind: MaskKind, image: MlImage, photoKey: String = ""): MaskRequest =
            MaskRequest(
                photoKey = photoKey,
                image = image,
                kind = kind,
                inputVersion = MaskInputs.VERSION,
                model = component.model.ifEmpty { null },
                prompts = component.prompts.map { MaskPrompt(it.x, it.y) },
                autoSeeds = component.autoSeeds || component.prompts.isEmpty(),
                refine = component.refine?.let {
                    RefineDefaults.Setting(it.radius.roundToInt().coerceAtLeast(1), it.eps.coerceAtLeast(MIN_EPS))
                },
            )

        private const val MIN_EPS = 1e-6f

        /** Logging never breaks the masks (plain JVM tests have no `android.util.Log`). */
        private inline fun log(write: () -> Unit) {
            runCatching(write)
        }
    }
}
