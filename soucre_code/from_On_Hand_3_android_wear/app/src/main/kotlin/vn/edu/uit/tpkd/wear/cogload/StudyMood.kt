package vn.edu.uit.tpkd.wear.cogload

/** Self-reported mood captured once before a study session starts. */
enum class StudyMood(val code: String, val label: String, val legacyFatigueScore: Int) {
    EXCELLENT("excellent", "Tuyệt vời", 1),
    GOOD("good", "Tốt", 3),
    NEUTRAL("neutral", "Bình thường", 5),
    NOT_GOOD("not_good", "Không tốt", 7),
    BAD("bad", "Xấu", 9);

    val isGoodOrBetter: Boolean
        get() = this == EXCELLENT || this == GOOD

    companion object {
        fun fromCode(code: String?): StudyMood = entries.firstOrNull { it.code == code } ?: NEUTRAL

        /** Migration for sessions saved before mood was introduced. */
        fun fromLegacyFatigue(score: Int): StudyMood = when (score) {
            in 1..2 -> EXCELLENT
            in 3..4 -> GOOD
            in 5..6 -> NEUTRAL
            in 7..8 -> NOT_GOOD
            else -> BAD
        }
    }
}
