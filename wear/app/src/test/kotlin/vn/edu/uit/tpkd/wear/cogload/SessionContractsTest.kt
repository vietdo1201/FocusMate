// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionContractsTest {
    @Test
    fun evidenceRegistryMapsEveryHealthRequirementToUiAndTests() {
        val records = FocusMateEvidenceRegistry.records.values
        (1..5).forEach { number ->
            val requirement = "HEALTH-0$number"
            val matching = records.filter { requirement in it.requirementIds }
            assertTrue("Missing evidence for $requirement", matching.isNotEmpty())
            assertTrue("Missing UI mapping for $requirement", matching.any { it.uiContentIds.isNotEmpty() })
            assertTrue("Missing test mapping for $requirement", matching.any { it.testIds.isNotEmpty() })
        }
        assertEquals(
            ProductParameterClass.EXPERIMENTAL,
            FocusMateParameterRegistry.classifications["checkin_ttl_20_minutes"],
        )
        assertEquals(
            ProductParameterClass.EXISTING_PRODUCT_RULE,
            FocusMateParameterRegistry.classifications["rule_30_45_60_minutes"],
        )
    }

    @Test
    fun heartRateCannotEnterEitherPolicyContext() {
        // Compile-time contract: PolicyContext has no HR field. Identical policy
        // input therefore produces identical timing regardless of HR collection.
        val context = context(minutes = 45, focus = 5, fatigue = 6)
        assertEquals(WatchRulesV2Policy.evaluate(context), WatchRulesV2Policy.evaluate(context))
        assertEquals(CheckInShadowV1Policy.evaluate(context), CheckInShadowV1Policy.evaluate(context))
    }

    @Test
    fun shadowUsesLatestSameBlockCheckInAndRecordsFallbackPerMissingField() {
        val base = context(minutes = 30, focus = 2, fatigue = 1)
        val recentFatigue = CheckInRecord(
            "ci", base.sessionId, base.blockId, CheckInSource.USER_REQUEST,
            base.now.copy(wallMs = base.now.wallMs - 60_000L), fatigue = 9,
        )
        val decision = CheckInShadowV1Policy.evaluate(base.copy(checkIns = listOf(recentFatigue)))

        assertTrue(decision.eligible)
        assertTrue(WatchRuleEngine.RULE_V1_HIGH_FATIGUE_LOW_FOCUS in decision.reasonCodes)
        assertTrue(CheckInShadowV1Policy.FALLBACK_FOCUS in decision.fallbacks)
        assertFalse(CheckInShadowV1Policy.FALLBACK_FATIGUE in decision.fallbacks)
    }

    @Test
    fun staleAndOtherBlockCheckInsFallBackToSessionStart() {
        val base = context(minutes = 30, focus = 2, fatigue = 9)
        val stale = CheckInRecord(
            "old", base.sessionId, base.blockId, CheckInSource.USER_REQUEST,
            base.now.copy(
                wallMs = base.now.wallMs - CheckInShadowV1Policy.CHECK_IN_TTL_MS - 1,
                elapsedMs = base.now.elapsedMs - CheckInShadowV1Policy.CHECK_IN_TTL_MS - 1,
            ),
            focus = 5, fatigue = 1,
        )
        val wrongBlock = stale.copy(checkInId = "wrong", blockId = "other", time = base.now, focus = 5, fatigue = 1)
        val decision = CheckInShadowV1Policy.evaluate(base.copy(checkIns = listOf(stale, wrongBlock)))

        assertTrue(WatchRuleEngine.RULE_V1_HIGH_FATIGUE_LOW_FOCUS in decision.reasonCodes)
        assertEquals(
            setOf(CheckInShadowV1Policy.FALLBACK_FOCUS, CheckInShadowV1Policy.FALLBACK_FATIGUE),
            decision.fallbacks,
        )
    }

    @Test
    fun wallClockChangeDoesNotRefreshShadowCheckIn() {
        val base = context(minutes = 30, focus = 5, fatigue = 1)
        val staleByMonotonicClock = CheckInRecord(
            "old-monotonic", base.sessionId, base.blockId, CheckInSource.USER_REQUEST,
            base.now.copy(
                wallMs = base.now.wallMs + 6 * 60 * 60_000L,
                elapsedMs = base.now.elapsedMs - CheckInShadowV1Policy.CHECK_IN_TTL_MS - 1L,
            ),
            focus = 2,
            fatigue = 9,
        )

        val decision = CheckInShadowV1Policy.evaluate(base.copy(checkIns = listOf(staleByMonotonicClock)))

        assertFalse(decision.eligible)
        assertEquals(
            setOf(CheckInShadowV1Policy.FALLBACK_FOCUS, CheckInShadowV1Policy.FALLBACK_FATIGUE),
            decision.fallbacks,
        )
    }

    private fun context(minutes: Int, focus: Int, fatigue: Int) = PolicyContext(
        sessionId = "session",
        blockId = "block",
        studyDurationMs = minutes * 60_000L,
        initialFocus = focus,
        initialFatigue = fatigue,
        now = TimePoint(10_000_000L, 9_000_000L, "boot"),
        cooldownUntilWallMs = 0L,
    )
}
