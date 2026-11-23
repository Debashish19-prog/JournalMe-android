package `in`.journalme.app.tracking

/**
 * Activity codes shared with the web layer (`ACTIVITY_CODE` in
 * web/src/platform/journalNative.ts). Keep the numbers in sync.
 */
object ActivityCode {
    const val UNKNOWN = 0
    const val STILL = 1
    const val WALK = 2
    const val RUN = 3
    const val CYCLE = 4
    const val VEHICLE = 5

    /** On foot, walking or running not yet known. */
    const val FOOT = 6

    fun isMoving(code: Int): Boolean = code in WALK..FOOT

    /** Moving on foot: easy to confuse with pottering around at home, so it needs more confidence. */
    fun isOnFoot(code: Int): Boolean = code == WALK || code == RUN || code == FOOT
}
