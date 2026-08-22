package vn.edu.uit.tpkd.wear.cogload

import org.junit.Assert.assertEquals
import org.junit.Test

class StudyMoodTest {
    @Test
    fun stableCodeResolvesToMood() {
        assertEquals(StudyMood.GOOD, StudyMood.fromCode("good"))
        assertEquals(StudyMood.NEUTRAL, StudyMood.fromCode("unknown"))
    }

    @Test
    fun legacyFatigueMapsToNearestMoodBand() {
        assertEquals(StudyMood.EXCELLENT, StudyMood.fromLegacyFatigue(1))
        assertEquals(StudyMood.NEUTRAL, StudyMood.fromLegacyFatigue(6))
        assertEquals(StudyMood.NOT_GOOD, StudyMood.fromLegacyFatigue(8))
        assertEquals(StudyMood.BAD, StudyMood.fromLegacyFatigue(10))
    }

    @Test
    fun fiveMoodOptionsRemainInRequiredOrder() {
        assertEquals(
            listOf("Tuyệt vời", "Tốt", "Bình thường", "Không tốt", "Xấu"),
            StudyMood.entries.map { it.label },
        )
    }
}
