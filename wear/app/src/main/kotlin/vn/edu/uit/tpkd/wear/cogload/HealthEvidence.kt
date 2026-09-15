// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

enum class EvidenceSourceType { PROFESSIONAL_GUIDANCE, EXPERIMENT, SYSTEMATIC_REVIEW }
enum class EvidenceAccess { FULL_TEXT, ABSTRACT_ONLY }
enum class ProductParameterClass { EXTERNAL_GUIDANCE, EXISTING_PRODUCT_RULE, ENGINEERING_DEFAULT, EXPERIMENTAL }

data class EvidenceRecord(
    val id: String,
    val sourceName: String,
    val authorsOrOrganization: String,
    val year: Int?,
    val doiOrUrl: String,
    val sourceType: EvidenceSourceType,
    val populationAndContext: String,
    val supportedConclusion: String,
    val limitations: String,
    val requirementIds: Set<String>,
    val uiContentIds: Set<String>,
    val testIds: Set<String>,
    val checkedOn: String,
    val access: EvidenceAccess,
)

/** Runtime-readable provenance for health wording and policy outputs. */
object FocusMateEvidenceRegistry {
    val records = listOf(
        EvidenceRecord(
            "HEALTH-01", "All About Heart Rate", "American Heart Association", null,
            "https://www.heart.org/en/health-topics/high-blood-pressure/the-facts-about-high-blood-pressure/all-about-heart-rate-pulse",
            EvidenceSourceType.PROFESSIONAL_GUIDANCE,
            "General heart-rate education; not a FocusMate fatigue study.",
            "Heart rate varies with activity, emotion, temperature, medication and other factors.",
            "A wrist BPM value cannot diagnose study fatigue, stress or recovery.",
            setOf("HEALTH-01"), setOf("report_heart_rate_safety"), setOf("HEALTH-06"),
            "2026-09-15", EvidenceAccess.FULL_TEXT,
        ),
        EvidenceRecord(
            "HEALTH-02", "Work routine and breaks", "UK Health and Safety Executive", null,
            "https://www.hse.gov.uk/msd/dse/work-routine.htm",
            EvidenceSourceType.PROFESSIONAL_GUIDANCE,
            "Occupational display-screen work.",
            "Short, frequent breaks and user control are useful design principles.",
            "It does not establish one medical break schedule for all students.",
            setOf("HEALTH-02"), setOf("break_suggestion", "voluntary_break"),
            setOf("RULE-01", "DELIVERY-01"), "2026-09-15", EvidenceAccess.FULL_TEXT,
        ),
        EvidenceRecord(
            "HEALTH-03", "Give me a break! A systematic review and meta-analysis on the efficacy of micro-breaks", "Albulescu et al.", 2022,
            "https://doi.org/10.1371/journal.pone.0272460",
            EvidenceSourceType.SYSTEMATIC_REVIEW,
            "22 independent samples, 2,335 participants, varied work tasks.",
            "Small average benefits were reported for fatigue and vigor.",
            "Overall performance improvement was not statistically significant; no causal claim for FocusMate.",
            setOf("HEALTH-03"), setOf("post_break_check_in", "session_report"),
            setOf("REPORT-01"), "2026-09-15", EvidenceAccess.FULL_TEXT,
        ),
        EvidenceRecord(
            "HEALTH-04", "Physical activity fact sheet", "World Health Organization", null,
            "https://www.who.int/news-room/fact-sheets/detail/physical-activity",
            EvidenceSourceType.PROFESSIONAL_GUIDANCE,
            "Population-level physical activity and sedentary-behaviour guidance.",
            "Reducing sedentary time and increasing appropriate activity are beneficial goals.",
            "One wrist sensor cannot establish whole-body activity, posture, health or concentration.",
            setOf("HEALTH-04"), setOf("motion_summary", "break_suggestion"),
            setOf("MOTION-01"), "2026-09-15", EvidenceAccess.FULL_TEXT,
        ),
        EvidenceRecord(
            "HEALTH-05-AOA", "Healthy screen habits and the 20-20-20 recommendation", "American Optometric Association", null,
            "https://www.aoa.org/healthy-eyes/caring-for-your-eyes/tis-the-season-to-be-stuck-inside",
            EvidenceSourceType.PROFESSIONAL_GUIDANCE,
            "General eye-care education for screen use.",
            "Looking away from a screen may be offered as an optional eye-rest habit.",
            "This guidance is not evidence that exact timing is a treatment or a complete study break.",
            setOf("HEALTH-05"), setOf("eye_rest_guidance"), setOf("HEALTH-05"),
            "2026-09-15", EvidenceAccess.FULL_TEXT,
        ),
        EvidenceRecord(
            "HEALTH-05-STUDY", "Is the 20-20-20 Rule Effective in Reducing Digital Eye Strain?", "Johnson & Rosenfield", 2023,
            "https://pubmed.ncbi.nlm.nih.gov/36473088/",
            EvidenceSourceType.EXPERIMENT,
            "30 young adults performing a demanding 40-minute tablet reading task.",
            "Eye rests may be offered as optional education for screen use.",
            "The study did not confirm benefit for exact 20-20-20 timing; eye rest is not a full break.",
            setOf("HEALTH-05"), setOf("eye_rest_guidance"), setOf("HEALTH-05"),
            "2026-09-15", EvidenceAccess.ABSTRACT_ONLY,
        ),
    ).associateBy(EvidenceRecord::id)
}

object FocusMateParameterRegistry {
    val classifications = mapOf(
        "rule_30_45_60_minutes" to ProductParameterClass.EXISTING_PRODUCT_RULE,
        "cooldown_20_minutes" to ProductParameterClass.EXISTING_PRODUCT_RULE,
        "break_5_minutes" to ProductParameterClass.EXISTING_PRODUCT_RULE,
        "motion_coverage_80_percent" to ProductParameterClass.EXISTING_PRODUCT_RULE,
        "motion_freshness_60_seconds" to ProductParameterClass.EXISTING_PRODUCT_RULE,
        "retry_5_minutes" to ProductParameterClass.ENGINEERING_DEFAULT,
        "retry_late_grace_5_minutes" to ProductParameterClass.ENGINEERING_DEFAULT,
        "checkpoint_30_seconds" to ProductParameterClass.ENGINEERING_DEFAULT,
        "motion_gap_tolerance_2_seconds" to ProductParameterClass.ENGINEERING_DEFAULT,
        "checkin_ttl_20_minutes" to ProductParameterClass.EXPERIMENTAL,
    )
}
