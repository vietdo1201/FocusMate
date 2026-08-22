package vn.edu.uit.tpkd.wear.cogload

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Small bounded local JSONL log for diagnosing alarms that were late or silenced. */
object ReminderDiagnostics {
    private const val FILE_NAME = "focusmate_reminder_diagnostics.jsonl"
    private const val MAX_BYTES = 128 * 1024L

    @Synchronized
    fun record(
        context: Context,
        reminder: PendingReminder,
        device: String,
        delivered: Boolean,
        reason: String? = null,
        receivedAtMs: Long = System.currentTimeMillis(),
    ) {
        runCatching {
            val file = File(context.noBackupFilesDir, FILE_NAME)
            prune(context)
            if (file.length() > MAX_BYTES) file.writeText("")
            val plannedAt = when (reminder.attempt.coerceAtLeast(1)) {
                1 -> reminder.createdAtMs
                2 -> reminder.createdAtMs + BreakReminderPolicy.RETRY_OFFSETS_MS[1]
                else -> reminder.createdAtMs + BreakReminderPolicy.RETRY_OFFSETS_MS[2]
            }
            val row = JSONObject().apply {
                put("event_id", reminder.eventId)
                put("kind", reminder.kind.wireValue)
                put("device", device)
                put("attempt", reminder.attempt)
                put("planned_at", plannedAt)
                put("received_at", receivedAtMs)
                put("late_by_ms", (receivedAtMs - plannedAt).coerceAtLeast(0L))
                put("delivered", delivered)
                put("reason", reason ?: JSONObject.NULL)
                put("expires_on", RetentionPolicy.expiresOnText(receivedAtMs))
            }
            file.appendText(row.toString() + "\n")
        }
    }

    /** Generic runtime failure (e.g. sensor service could not start) in the same bounded log. */
    @Synchronized
    fun recordEvent(
        context: Context,
        code: String,
        detail: String? = null,
        atMs: Long = System.currentTimeMillis(),
    ) {
        runCatching {
            val file = File(context.noBackupFilesDir, FILE_NAME)
            prune(context)
            if (file.length() > MAX_BYTES) file.writeText("")
            val row = JSONObject().apply {
                put("kind", "runtime_event")
                put("device", "watch")
                put("code", code)
                put("detail", detail ?: JSONObject.NULL)
                put("received_at", atMs)
                put("expires_on", RetentionPolicy.expiresOnText(atMs))
            }
            file.appendText(row.toString() + "\n")
        }
    }

    @Synchronized
    fun clear(context: Context) {
        runCatching { File(context.noBackupFilesDir, FILE_NAME).delete() }
    }

    @Synchronized
    fun prune(context: Context, today: LocalDate = LocalDate.now()): Int {
        val file = File(context.noBackupFilesDir, FILE_NAME)
        if (!file.isFile) return 0
        val rows = file.readLines()
        val retained = rows.filter { line ->
            val json = runCatching { JSONObject(line) }.getOrNull() ?: return@filter false
            val observed = json.optLong("received_at", 0L)
            val expires = json.optString("expires_on").takeIf(String::isNotBlank)
            observed > 0L && !RetentionPolicy.isExpired(
                expiresOn = expires,
                fallbackObservedAtMs = observed,
                today = today,
            )
        }
        if (retained.size != rows.size) file.writeText(retained.joinToString("\n", postfix = if (retained.isEmpty()) "" else "\n"))
        return rows.size - retained.size
    }
}
