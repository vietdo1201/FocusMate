package vn.edu.uit.tpkd.wear.cogload

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object RetentionPolicy {
    const val RETENTION_DAYS = 30L

    fun expiresOn(observedAtMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(observedAtMs).atZone(zoneId).toLocalDate().plusDays(RETENTION_DAYS)

    fun expiresOnText(observedAtMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        expiresOn(observedAtMs, zoneId).toString()

    fun isExpired(
        expiresOn: String?,
        fallbackObservedAtMs: Long,
        today: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        val parsed = expiresOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return isExpired(parsed ?: expiresOn(fallbackObservedAtMs, zoneId), today)
    }

    fun isExpired(expiresOn: LocalDate, today: LocalDate = LocalDate.now()): Boolean = !today.isBefore(expiresOn)
}
