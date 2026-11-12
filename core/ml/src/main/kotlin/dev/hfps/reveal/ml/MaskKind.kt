package dev.hfps.reveal.ml

/**
 * What a mask selects (docs/adr/0021). M4's AI masks are these kinds; a segmentation model declares which ones it
 * produces ([MaskModel.kinds]); the face parts ([isFacePart]) are polygons drawn from face landmarks
 * ([dev.hfps.reveal.ml.face.FaceParts]). People parts come from the person segmenter's classes, so they are only as
 * good as it is. Names are stored in recipes and cache keys: never rename one.
 */
enum class MaskKind {
    /** The main subject of the photo, of any kind (a person, an animal, an object). */
    SUBJECT,

    /** Everything but the subject: the inverse of [SUBJECT]. */
    BACKGROUND,

    /** The sky (clouds included). */
    SKY,

    /** Every person in the frame, together. */
    PEOPLE,

    /** Facial skin. */
    FACE_SKIN,

    /** Skin other than the face (arms, hands, legs, neck). */
    BODY_SKIN,

    /** All visible skin: [FACE_SKIN] + [BODY_SKIN]. */
    SKIN,

    /** Hair (head hair; beards are mostly face skin to the model). */
    HAIR,

    /** Clothes. */
    CLOTHES,

    /** The eyes' openings (iris, pupil and the white of the eye) between the lids, every face. */
    EYES,

    /** The irises (and pupils), clipped to the eyes' openings. */
    IRISES,

    /** The lips: the outer contour of the mouth minus its inner opening. */
    LIPS,

    /** The mouth's inner opening (teeth, tongue), when the mouth is open. */
    TEETH,

    /** The eyebrows. */
    EYEBROWS,
    ;

    /** Drawn from face landmarks, not segmented. */
    val isFacePart: Boolean get() = this in FACE_PARTS

    private companion object {
        val FACE_PARTS = setOf(EYES, IRISES, LIPS, TEETH, EYEBROWS)
    }
}
