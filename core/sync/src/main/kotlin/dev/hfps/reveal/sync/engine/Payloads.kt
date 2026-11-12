package dev.hfps.reveal.sync.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Record kinds of ADR 0017. */
object Kinds {
    const val PHOTO = "photo"
    const val VERSION = "version"
    const val PRESET = "preset"
    const val SETTING = "setting"
}

/** One file of a photo's shot (ADR 0017 "Records", `photo.data.files`). */
@Serializable
data class FileData(
    val fingerprint: String,
    /** `RAW`, `RENDERED` or `OTHER` (`FileRole`). */
    val role: String,
    val name: String,
    val size: Long,
    val sha256: String? = null,
)

/** A photo's smart preview (`photo.data.preview`): the blob and its pixel size. */
@Serializable
data class PreviewRef(val sha256: String, val width: Int, val height: Int)

/**
 * `photo.data` (ADR 0017, ADR 0018): the main file's [fingerprint] (the RAW of a pair), the shot's files and the
 * library metadata. [captureTime] is the camera clock as UTC millis (`photos.capture_time`).
 */
@Serializable
data class PhotoData(
    val fingerprint: String,
    val files: List<FileData> = emptyList(),
    val name: String = "",
    val format: String = "",
    val rating: Int = 0,
    /** `NONE`, `PICK` or `REJECT`. */
    val flag: String = "NONE",
    val label: String? = null,
    val captureTime: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val hidden: Boolean = false,
    val preview: PreviewRef? = null,
)

/** One step of `version.data.history`: the recipe after the step (ADR 0003 JSON) and its labels (ADR 0006). */
@Serializable
data class StepData(val recipe: JsonElement, val label: JsonElement)

/**
 * `version.data` (ADR 0017): the photo it belongs to, its place among the photo's versions, the committed recipe, the
 * history steps and the undo cursor (-1 = before the first step).
 */
@Serializable
data class VersionData(
    val photoId: String,
    val position: Int = 0,
    val name: String? = null,
    val recipe: JsonElement,
    val history: List<StepData> = emptyList(),
    val cursor: Int = -1,
)

/** `setting.data` of `export.authorship`: the author and copyright exports write (ADR 0009). */
@Serializable
data class AuthorshipData(val author: String = "", val copyright: String = "")
