package com.capstone.stethoscope.data.model

/**
 * Where on the chest a recording was taken.
 *
 * The five classical praecordial auscultation positions. They are not interchangeable:
 * each sits over the point where one valve's sounds transmit best, so the same heart
 * produces a visibly different phonocardiogram at each one. A recording without a site is
 * a recording nobody can reproduce or compare, which is why this is captured at all.
 *
 * [x] and [y] are fractions of the chest diagram's width and height, measured from its
 * top-left — the centres of the coloured dots in `res/drawable-nodpi/chest_diagram.png`.
 * **Replace that image and these must be re-measured**, or taps and the PDF marker land
 * beside the dots instead of on them. They follow the convention of every anatomical illustration: **the diagram faces
 * the viewer, so the patient's right side is on the left of the screen.** Aortic is the
 * patient's right, which is why its [x] is below 0.5.
 *
 * Sites are stored on the recording in plaintext. Unlike a name or an ID, a valve
 * position identifies nobody — and the free-text [Recording.siteNote] beside it, which
 * could contain anything, is encrypted.
 */
enum class AuscultationSite(
    /** Stable key written to the database. Never rename these. */
    val key: String,
    val x: Float,
    val y: Float,
) {
    /** 2nd intercostal space, right sternal border. */
    AORTIC("AORTIC", 0.453f, 0.422f),

    /** 2nd intercostal space, left sternal border. */
    PULMONIC("PULMONIC", 0.566f, 0.422f),

    /** 3rd intercostal space, left sternal border. Erb's point — S2 is loudest here. */
    ERBS("ERBS", 0.586f, 0.539f),

    /** 4th intercostal space, lower left sternal border. */
    TRICUSPID("TRICUSPID", 0.574f, 0.731f),

    /** 5th intercostal space, midclavicular line — the apex. */
    MITRAL("MITRAL", 0.689f, 0.828f),
    ;

    companion object {
        /** Parses a stored [key], tolerating a row written by a future version. */
        fun fromKey(key: String?): AuscultationSite? =
            key?.let { k -> entries.firstOrNull { it.key == k } }
    }
}
