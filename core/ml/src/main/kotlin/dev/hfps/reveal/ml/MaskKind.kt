package dev.hfps.reveal.ml

/**
 * What a mask selects (docs/adr/0021). M4's AI masks are these kinds; a model declares which ones it produces
 * ([MaskModel.kinds]). People parts come from the person segmenter's classes, so they are only as good as it is.
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
}
