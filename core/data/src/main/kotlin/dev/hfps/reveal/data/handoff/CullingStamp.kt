package dev.hfps.reveal.data.handoff

/**
 * Who decided a culling field and when: the pair last-writer-wins compares (Hoard ADR 0016 "Client rules", Reveal ADR
 * 0027 and ADR 0011 "Amendment W3/H"). One shared comparator, so a hand-off, the sidecar import and the sync engine
 * agree with the server on every tie.
 */
data class CullingStamp(val modifiedAt: Long, val deviceId: String? = null) {
    /** True when this change beats [other]: the later time, and at equal times the greater device id (ordinal). */
    fun beats(other: CullingStamp): Boolean = compare(this, other) > 0

    companion object {
        /**
         * Orders [a] and [b] as the server does: by `modifiedAt`, then by `deviceId` compared **byte-wise** on its
         * UTF-8
         * form (Go's string order, never a locale collator and not Kotlin's UTF-16 order, which differs beyond the
         * BMP). A missing device id sorts before any present one; two equal stamps compare 0.
         */
        fun compare(a: CullingStamp, b: CullingStamp): Int {
            val byTime = a.modifiedAt.compareTo(b.modifiedAt)
            return if (byTime != 0) byTime else compareIds(a.deviceId, b.deviceId)
        }

        /** Byte-wise order of two device ids (null first). */
        @Suppress("ReturnCount")
        fun compareIds(a: String?, b: String?): Int {
            if (a == null || b == null) {
                return if (a == b) {
                    0
                } else if (a == null) {
                    -1
                } else {
                    1
                }
            }
            val x = a.toByteArray(Charsets.UTF_8)
            val y = b.toByteArray(Charsets.UTF_8)
            for (i in 0 until minOf(x.size, y.size)) {
                val d = (x[i].toInt() and BYTE) - (y[i].toInt() and BYTE)
                if (d != 0) return d
            }
            return x.size - y.size
        }

        private const val BYTE = 0xff
    }
}
