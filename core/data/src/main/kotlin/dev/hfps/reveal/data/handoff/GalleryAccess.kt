package dev.hfps.reveal.data.handoff

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How much of the device's photo library Reveal may read (ADR 0011 "Gallery access"). */
enum class GalleryAccessState {
    /** Every photo ("Photos and videos" allowed). */
    FULL,

    /** Only the photos the owner selected (Android 14+ "Allow limited access"). */
    PARTIAL,

    /** None: a gallery hand-off is readable only while its temporary grant lasts. */
    NONE,
}

/**
 * The optional "Photos and videos" permission (ADR 0011 "Gallery access", option B): with it, a MediaStore URI a
 * gallery handed over (`content://media/...`) stays readable after the temporary grant ends, so the photo gets its
 * full thumbnail, export and original backup back. Never asked out of the blue: the library offers it once, in a sheet,
 * when such a hand-off became unreadable; declining is remembered, and Settings can turn it on later.
 */
class GalleryAccess(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val current = MutableStateFlow(read())
    private val askedState = MutableStateFlow(preferences.getBoolean(KEY_ASKED, false))
    private val mediaAskedState = MutableStateFlow(preferences.getBoolean(KEY_MEDIA_ASKED, false))
    private val hoardMediaState = MutableStateFlow(preferences.getBoolean(KEY_HOARD_MEDIA, false))

    /** The permission as last read ([refresh] after a permission result or when the app comes back). */
    val state: StateFlow<GalleryAccessState> = current.asStateFlow()

    /** The library's offer was answered (allowed or declined): it is not shown again. */
    val asked: StateFlow<Boolean> = askedState.asStateFlow()

    /** The offer made on receipt of Hoard's MediaStore photos was answered (its own flag, ADR 0011 "Amendment W4"). */
    val mediaAsked: StateFlow<Boolean> = mediaAskedState.asStateFlow()

    /** Hoard handed over photos of the `MediaStore.Images` collection ([noteHoardMedia]). */
    val hoardMedia: StateFlow<Boolean> = hoardMediaState.asStateFlow()

    /** Reads the permission again; true when it changed. */
    fun refresh(): Boolean {
        val now = read()
        val changed = now != current.value
        current.value = now
        return changed
    }

    fun setAsked() {
        preferences.edit { putBoolean(KEY_ASKED, true) }
        askedState.value = true
    }

    fun setMediaAsked() {
        preferences.edit { putBoolean(KEY_MEDIA_ASKED, true) }
        mediaAskedState.value = true
    }

    /** A hand-off from Hoard held `MediaStore.Images` URIs: the media offer may be shown (once). */
    fun noteHoardMedia() {
        if (hoardMediaState.value) return
        preferences.edit { putBoolean(KEY_HOARD_MEDIA, true) }
        hoardMediaState.value = true
    }

    /** Forgets that the offers were answered and that Hoard sent media (instrumented tests only). */
    fun resetAskedForTests() {
        preferences.edit {
            putBoolean(KEY_ASKED, false)
            putBoolean(KEY_MEDIA_ASKED, false)
            putBoolean(KEY_HOARD_MEDIA, false)
        }
        askedState.value = false
        mediaAskedState.value = false
        hoardMediaState.value = false
    }

    private fun read(): GalleryAccessState = stateOf(Build.VERSION.SDK_INT) {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val PREFERENCES = "gallery_access"
        private const val KEY_ASKED = "asked"
        private const val KEY_MEDIA_ASKED = "mediaAsked"
        private const val KEY_HOARD_MEDIA = "hoardMedia"

        /** `READ_MEDIA_IMAGES` (Android 13; named here, not through `Manifest`, so older SDK levels compile it). */
        const val IMAGES = "android.permission.READ_MEDIA_IMAGES"

        /** `READ_MEDIA_VISUAL_USER_SELECTED` (Android 14). */
        const val USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

        /** `READ_EXTERNAL_STORAGE` (up to Android 12). */
        const val STORAGE = "android.permission.READ_EXTERNAL_STORAGE"

        /** The permissions to request on [sdk]: images (and the partial grant on Android 14+), or storage below 13. */
        fun permissions(sdk: Int = Build.VERSION.SDK_INT): Array<String> = when {
            sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(IMAGES, USER_SELECTED)
            sdk >= Build.VERSION_CODES.TIRAMISU -> arrayOf(IMAGES)
            else -> arrayOf(STORAGE)
        }

        /** The state on [sdk] from which permissions are [granted]. */
        fun stateOf(sdk: Int, granted: (String) -> Boolean): GalleryAccessState = when {
            sdk >= Build.VERSION_CODES.TIRAMISU && granted(IMAGES) ->
                GalleryAccessState.FULL

            sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && granted(USER_SELECTED) -> GalleryAccessState.PARTIAL

            sdk < Build.VERSION_CODES.TIRAMISU && granted(STORAGE) ->
                GalleryAccessState.FULL

            else -> GalleryAccessState.NONE
        }

        /** A MediaStore document (what a gallery's `SEND` hands over), which the permission makes readable. */
        fun isMediaStore(documentId: String): Boolean = documentId.startsWith("content://media/")

        /**
         * A document of the `MediaStore.Images` side that the photo permission covers: a MediaStore URI outside the
         * Downloads collection (Downloads files are copied while the grant lives instead, `LocalOriginals`).
         */
        fun isMediaImages(documentId: String): Boolean = isMediaStore(documentId) && "/downloads/" !in documentId

        /**
         * Whether the library offers the permission now: a MediaStore hand-off cannot be read, Reveal has no access to
         * the gallery, and the offer was never answered.
         */
        fun shouldOffer(unreadable: List<String>, state: GalleryAccessState, asked: Boolean): Boolean =
            !asked && state == GalleryAccessState.NONE && unreadable.any(::isMediaStore)

        /**
         * Whether to offer the permission on receipt of Hoard's MediaStore photos (ADR 0011 "Amendment W4": Hoard hands
         * over `content://media/...` URIs of the Images collection): once ([mediaAsked], its own flag), while access is
         * not FULL, in the library for a batch or in the editor for a single photo. With [GalleryAccessState.PARTIAL]
         * the sheet explains that only the selected photos stay openable. Settings changes the answer later.
         */
        fun shouldOfferForMedia(hoardMedia: Boolean, state: GalleryAccessState, mediaAsked: Boolean): Boolean =
            !mediaAsked && hoardMedia && state != GalleryAccessState.FULL
    }
}
