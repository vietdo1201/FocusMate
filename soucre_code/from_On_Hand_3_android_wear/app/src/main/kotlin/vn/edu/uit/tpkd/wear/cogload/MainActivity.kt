package vn.edu.uit.tpkd.wear.cogload

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil

/** Wear session manager with one deterministic break-decision authority. */
class MainActivity : Activity() {
    private lateinit var repository: StudySessionRepository

    private lateinit var tvSessionState: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvBreakTimer: TextView
    private lateinit var btnTask: Button
    private lateinit var btnFocusMinus: Button
    private lateinit var btnFocusPlus: Button
    private lateinit var tvFocus: TextView
    private lateinit var btnFatigueMinus: Button
    private lateinit var btnFatiguePlus: Button
    private lateinit var tvFatigue: TextView
    private lateinit var btnPrimary: Button
    private lateinit var btnCancel: Button
    private lateinit var tvCooldown: TextView
    private lateinit var tvSessionConfidence: TextView
    private lateinit var tvRuleStatus: TextView
    private lateinit var tvPostureStatus: TextView
    private lateinit var tvTodayTotal: TextView
    private lateinit var weeklyChart: WeeklyStudyChart
    private lateinit var tvStats: TextView
    private lateinit var tvRecent: TextView
    private lateinit var tvPostureRuntimeStatus: TextView
    private lateinit var tvReminderReadiness: TextView
    private lateinit var btnReminderSettings: Button
    private lateinit var btnTestReminder: Button

    private var accCollector: AccCollector? = null
    private var isCollecting = false
    private var isResumed = false
    private var promptVisible = false
    private var breakChoiceVisible = false
    private var reviewVisible = false
    private var dndAccessRequested = false
    private val activityThresholdCalibrator = PersonalActivityThresholdCalibrator()

    private val uiHandler = Handler(Looper.getMainLooper())
    private val taskTypes = listOf("Bài tập", "Đọc tài liệu", "Ôn thi", "Lập trình")
    private var taskIndex = 0
    private var focusScore = 3
    private var fatigueScore = 5

    private val ticker = object : Runnable {
        override fun run() {
            maybeShowBreakEndChoice()
            renderLiveState()
            val active = repository.activeSession()
            renderSessionConfidence(active)
            renderRuleStatus(active)
            renderPostureStatus(active)
            renderPostureRuntimeStatus(active)
            maybeShowForegroundBreakPrompt()
            uiHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchRetentionWorker.schedule(this)
        setContentView(R.layout.activity_main)
        repository = StudySessionRepository(this)
        bindViews()
        bindActions()
        prepareMotionFallbackCollector()
        renderAll()
        syncStudyDnd(isCurrentlyStudying(repository.activeSession()))
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        BreakAlertChannels.ensureStandard(this)
        BreakAlertChannels.ensurePriorityIfAllowed(this)
        renderAll()
        val active = repository.activeSession()
        syncStudyDnd(isCurrentlyStudying(active))
        if (active != null) {
            BreakReminderScheduler.schedule(this, active, repository.cooldownUntilMs())
        }
        updateSensorCollection()
        uiHandler.removeCallbacks(ticker)
        uiHandler.post(ticker)
        uiHandler.postDelayed({ maybeShowPendingReview() }, 300L)
    }

    override fun onPause() {
        isResumed = false
        uiHandler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        accCollector?.stop()
        super.onDestroy()
    }

    private fun bindViews() {
        tvSessionState = findViewById(R.id.tv_session_state)
        tvTimer = findViewById(R.id.tv_timer)
        tvBreakTimer = findViewById(R.id.tv_break_timer)
        btnTask = findViewById(R.id.btn_task)
        btnFocusMinus = findViewById(R.id.btn_focus_minus)
        btnFocusPlus = findViewById(R.id.btn_focus_plus)
        tvFocus = findViewById(R.id.tv_focus)
        btnFatigueMinus = findViewById(R.id.btn_fatigue_minus)
        btnFatiguePlus = findViewById(R.id.btn_fatigue_plus)
        tvFatigue = findViewById(R.id.tv_fatigue)
        btnPrimary = findViewById(R.id.btn_primary)
        btnCancel = findViewById(R.id.btn_cancel)
        tvCooldown = findViewById(R.id.tv_cooldown)
        tvSessionConfidence = findViewById(R.id.tv_session_confidence)
        tvRuleStatus = findViewById(R.id.tv_rule_status)
        tvPostureStatus = findViewById(R.id.tv_posture_status)
        tvTodayTotal = findViewById(R.id.tv_today_total)
        weeklyChart = findViewById(R.id.weekly_chart)
        tvStats = findViewById(R.id.tv_stats)
        tvRecent = findViewById(R.id.tv_recent)
        tvPostureRuntimeStatus = findViewById(R.id.tv_posture_runtime_status)
        tvReminderReadiness = findViewById(R.id.tv_reminder_readiness)
        btnReminderSettings = findViewById(R.id.btn_reminder_settings)
        btnTestReminder = findViewById(R.id.btn_test_reminder)
    }

    private fun bindActions() {
        btnTask.setOnClickListener {
            if (repository.activeSession() == null) {
                taskIndex = (taskIndex + 1) % taskTypes.size
                renderInputs()
            }
        }
        btnFatigueMinus.setOnClickListener { updateFatigue(-1) }
        btnFatiguePlus.setOnClickListener { updateFatigue(1) }
        btnFocusMinus.setOnClickListener { updateFocus(-1) }
        btnFocusPlus.setOnClickListener { updateFocus(1) }
        btnPrimary.setOnClickListener {
            if (repository.activeSession() == null) {
                if (isReminderReady()) {
                    startSession()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Nhắc nghỉ chưa sẵn sàng")
                        .setMessage("Phiên vẫn có thể chạy nhưng đồng hồ có thể không rung hoặc báo trễ. Bạn vẫn muốn bắt đầu?")
                        .setPositiveButton("Vẫn bắt đầu") { _, _ -> startSession() }
                        .setNegativeButton("Sửa thiết lập") { _, _ -> openNextReminderSetting() }
                        .show()
                }
            } else {
                confirmFinishSession()
            }
        }
        btnCancel.setOnClickListener { confirmCancelSession() }
        btnReminderSettings.setOnClickListener { openNextReminderSetting() }
        btnTestReminder.setOnClickListener {
            val shown = BreakReminderScheduler.showTestAlert(this)
            Toast.makeText(
                this,
                if (shown) "Đã gửi rung thử." else "Notification đang bị tắt.",
                Toast.LENGTH_LONG,
            ).show()
            renderReminderReadiness()
        }
    }

    private fun startSession() {
        val active = ActiveStudySession(
            startTimeMs = System.currentTimeMillis(),
            subject = HIDDEN_SUBJECT,
            taskType = taskTypes[taskIndex],
            focusScore = focusScore,
            fatigueScore = fatigueScore,
            breakTargetMinutes = repository.recommendedBreakTargetMinutes(),
        )
        repository.saveActiveSession(active)
        syncStudyDnd(true)
        BreakReminderScheduler.schedule(this, active, repository.cooldownUntilMs())
        requestSessionPermissionsIfNeeded()
        renderAll()
        updateSensorCollection()
        Toast.makeText(this, "Đã bắt đầu phiên học", Toast.LENGTH_SHORT).show()
    }

    private fun confirmFinishSession() {
        AlertDialog.Builder(this)
            .setTitle("Kết thúc phiên học?")
            .setMessage("Lưu thời lượng, mức tập trung, mức mệt và dữ liệu cảm biến?")
            .setPositiveButton("Lưu phiên") { _, _ -> finishSession() }
            .setNegativeButton("Tiếp tục", null)
            .show()
    }

    private fun finishSession() {
        val completed = repository.finishActiveSession() ?: return
        BreakReminderScheduler.cancel(this)
        syncStudyDnd(false)
        stopSensorCollection()
        renderAll()
        val message = if (completed.shouldBreak) {
            "Đã lưu phiên ${completed.durationMinutes} phút. Rule v2 ghi nhận nên nghỉ."
        } else {
            "Đã lưu phiên ${completed.durationMinutes} phút."
        }
        val postureReport = PostureRecommendations.report(completed.postureSummaries)
        AlertDialog.Builder(this)
            .setTitle("Báo cáo cuối phiên")
            .setMessage(if (postureReport.isBlank()) "$message\nTư thế: chưa có dữ liệu." else "$message\n\n$postureReport")
            .setPositiveButton("Đóng", null)
            .show()
        uiHandler.post { maybeShowPendingReview() }
    }

    private fun confirmCancelSession() {
        AlertDialog.Builder(this)
            .setTitle("Hủy phiên đang học?")
            .setMessage("Phiên này sẽ không được ghi vào lịch sử.")
            .setPositiveButton("Hủy phiên") { _, _ ->
                repository.cancelActiveSession()
                BreakReminderScheduler.cancel(this)
                syncStudyDnd(false)
                stopSensorCollection()
                renderAll()
            }
            .setNegativeButton("Giữ lại", null)
            .show()
    }

    private fun updateFatigue(delta: Int) {
        if (repository.activeSession() != null) return
        fatigueScore = (fatigueScore + delta).coerceIn(1, 10)
        renderInputs()
    }

    private fun updateFocus(delta: Int) {
        if (repository.activeSession() != null) return
        focusScore = (focusScore + delta).coerceIn(1, 5)
        renderInputs()
    }

    private fun maybeShowForegroundBreakPrompt() {
        if (!isResumed || promptVisible) return
        val active = repository.activeSession() ?: return
        val now = System.currentTimeMillis()
        if (StudySessionClock.isOnBreak(active, now)) return
        val pending = active.pendingReminder
        if (pending?.kind == PendingReminderKind.BREAK_SUGGESTION) {
            promptVisible = true
            val dialog = AlertDialog.Builder(this)
                .setTitle("Đã đến lúc nghỉ")
                .setMessage(pending.message)
                .setCancelable(false)
                .setPositiveButton("Nghỉ 5 phút") { _, _ ->
                    promptVisible = false
                    sendReminderAction(
                        BreakReminderScheduler.ACTION_ACCEPT,
                        active.sessionId,
                        pending.eventId,
                    )
                }
                .setNegativeButton("Để sau 20 phút") { _, _ ->
                    promptVisible = false
                    sendReminderAction(
                        BreakReminderScheduler.ACTION_DEFER,
                        active.sessionId,
                        pending.eventId,
                    )
                }
                .create()
            dialog.setOnDismissListener { promptVisible = false }
            dialog.show()
            return
        }
        val durationMs = repository.focusBlockDurationMs(active, now)
        val decision = repository.evaluateBreak(
            active = active,
            durationMs = durationMs,
            nowMs = now,
        )
        val duplicateGuardPassed = now - active.lastPromptAtMs >= FocusMateRules.DUPLICATE_PROMPT_GUARD_MS
        if (!decision.shouldPrompt) return
        if (!duplicateGuardPassed) return
        BreakReminderScheduler.requestImmediateCheck(this)
    }

    private fun maybeShowPendingReview() {
        if (!isResumed || promptVisible || reviewVisible) return
        val session = repository.pendingReviewSession() ?: return
        reviewVisible = true
        val dialog = AlertDialog.Builder(this)
            .setTitle("Đánh giá thời điểm nhắc")
            .setMessage("Theo bạn, trong phiên vừa rồi có nên được nhắc nghỉ không?")
            .setPositiveButton("Nên nhắc") { _, _ ->
                repository.updateSessionReview(session.sessionId, true)
                renderHistory()
            }
            .setNegativeButton("Không cần") { _, _ ->
                repository.updateSessionReview(session.sessionId, false)
                renderHistory()
            }
            .setNeutralButton("Bỏ qua") { _, _ -> repository.clearPendingReview() }
            .setCancelable(false)
            .create()
        dialog.setOnDismissListener { reviewVisible = false }
        dialog.show()
    }

    private fun showActiveDeferReason(sessionId: String, eventId: String) {
        val reasons = arrayOf(
            BreakPromptEvent.DECLINE_FOCUS_SEGMENT to "Đang dở việc",
            BreakPromptEvent.DECLINE_ALMOST_DONE to "Sắp hoàn thành",
            BreakPromptEvent.DECLINE_NOT_TIRED to "Chưa thấy mệt",
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle("Vì sao bạn muốn hoãn?")
            .setCancelable(false)
            .setItems(reasons.map { it.second }.toTypedArray()) { _, index ->
                val active = repository.activeSession()
                if (active != null && active.sessionId == sessionId) {
                    val now = System.currentTimeMillis()
                    val cooldownUntil = now + FocusMateRules.COOLDOWN_MS
                    val updated = runCatching {
                        repository.recordPromptResponse(
                            sessionId = sessionId,
                            eventId = eventId,
                            accepted = false,
                            declineReasonCode = reasons[index].first,
                            sessionDeferReason = reasons[index].second,
                            respondedAtMs = now,
                            quietUntilMs = cooldownUntil,
                            requireCurrentEvent = false,
                        )
                    }.getOrNull() ?: active.copy(accepted = false, deferReason = reasons[index].second).also {
                        repository.saveActiveSession(it)
                        repository.setCooldownUntilMs(cooldownUntil)
                    }
                    BreakReminderScheduler.schedule(this, updated, cooldownUntil)
                    renderAll()
                    Toast.makeText(this, "Sẽ không làm phiền trong 20 phút.", Toast.LENGTH_LONG).show()
                }
            }
            .create()
        dialog.setOnDismissListener { promptVisible = false }
        dialog.show()
    }

    private fun renderAll() {
        val active = repository.activeSession()
        if (active != null) {
            taskIndex = taskTypes.indexOf(active.taskType).takeIf { it >= 0 } ?: taskIndex
            fatigueScore = active.fatigueScore.coerceIn(1, 10)
            focusScore = active.focusScore
        }
        val running = active != null
        val onBreak = active?.let { StudySessionClock.isOnBreak(it, System.currentTimeMillis()) } == true
        tvSessionState.setText(
            when {
                !running -> R.string.state_ready
                onBreak -> R.string.state_break
                else -> R.string.state_studying
            }
        )
        btnPrimary.setText(if (running) R.string.finish_and_save else R.string.start_study)
        btnCancel.visibility = if (running) View.VISIBLE else View.GONE
        btnTask.isEnabled = !running
        renderInputs()
        renderLiveState()
        renderSessionConfidence(active)
        renderRuleStatus(active)
        renderPostureStatus(active)
        renderHistory()
        renderPostureRuntimeStatus(active)
        renderReminderReadiness()
    }

    private fun isReminderReady(): Boolean {
        val notificationPermissionReady =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return notificationPermissionReady &&
            BreakReminderScheduler.exactAlarmsReady(this) &&
            StudyDndController.hasAccess(this) &&
            BreakAlertChannels.notificationsReady(this) &&
            BreakAlertChannels.priorityReady(this)
    }

    private fun renderReminderReadiness() {
        val notificationReady =
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                BreakAlertChannels.notificationsReady(this)
        val exactReady = BreakReminderScheduler.exactAlarmsReady(this)
        val dndReady = StudyDndController.hasAccess(this) && BreakAlertChannels.priorityReady(this)
        val allReady = notificationReady && exactReady && dndReady
        tvReminderReadiness.text = buildString {
            append(if (allReady) "● NHẮC NGHỈ ĐÃ SẴN SÀNG" else "● NHẮC NGHỈ CHƯA ĐẢM BẢO")
            append("\n")
            append(if (notificationReady) "✓ Notification + rung" else "✕ Notification + rung")
            append("  ")
            append(if (exactReady) "✓ Đúng giờ" else "✕ Có thể báo trễ")
            append("\n")
            append(if (dndReady) "✓ Xuyên Không làm phiền" else "✕ Chưa xuyên DND")
        }
        tvReminderReadiness.setTextColor(if (allReady) 0xFF72D9C8.toInt() else 0xFFFF8A80.toInt())
        btnReminderSettings.visibility = if (allReady) View.GONE else View.VISIBLE
    }

    private fun openNextReminderSetting() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_SESSION_PERMISSIONS)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !BreakReminderScheduler.exactAlarmsReady(this)
        ) {
            runCatching { startActivity(BreakReminderScheduler.exactAlarmSettingsIntent(this)) }
            return
        }
        if (!StudyDndController.hasAccess(this)) {
            StudyDndController.requestAccess(this)
            return
        }
        BreakAlertChannels.ensurePriorityIfAllowed(this)
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, BreakAlertChannels.PRIORITY_CHANNEL_ID)
        }
        runCatching { startActivity(intent) }
    }

    private fun renderInputs() {
        btnTask.text = taskTypes[taskIndex]
        tvFocus.text = getString(R.string.focus_value, focusScore)
        tvFatigue.text = getString(R.string.fatigue_value, fatigueScore)
        btnFocusMinus.isEnabled = repository.activeSession() == null && focusScore > 1
        btnFocusPlus.isEnabled = repository.activeSession() == null && focusScore < 5
        btnFatigueMinus.isEnabled = repository.activeSession() == null && fatigueScore > 1
        btnFatiguePlus.isEnabled = repository.activeSession() == null && fatigueScore < 10
    }

    private fun renderLiveState() {
        val now = System.currentTimeMillis()
        val active = repository.activeSession()
        if (active == null) {
            tvTimer.setText(R.string.timer_zero)
            tvBreakTimer.visibility = View.GONE
        } else {
            val durationMs = repository.studyDurationMs(active, now)
            tvTimer.text = formatDuration(durationMs)
            val breakRemainingMs = StudySessionClock.breakRemainingMs(active, now)
            val awaitingDecision = StudySessionClock.isAwaitingBreakDecision(active)
            tvBreakTimer.visibility = if (breakRemainingMs > 0L || awaitingDecision) View.VISIBLE else View.GONE
            tvBreakTimer.text = when {
                awaitingDecision -> getString(R.string.break_timer_awaiting_choice)
                breakRemainingMs > 0L -> getString(R.string.break_timer_value, formatShortDuration(breakRemainingMs))
                else -> ""
            }
        }
        val cooldown = (repository.cooldownUntilMs() - now).coerceAtLeast(0L)
        tvCooldown.visibility = if (cooldown > 0L) View.VISIBLE else View.GONE
        tvCooldown.text = if (cooldown > 0L) "Không làm phiền: ${formatShortDuration(cooldown)}" else ""
    }

    private fun renderSessionConfidence(active: ActiveStudySession?) {
        if (active == null) {
            tvSessionConfidence.setText(R.string.session_confidence_idle)
            return
        }
        tvSessionConfidence.text = getString(
            R.string.session_confidence_value,
            SessionConfidence.calculate(active, System.currentTimeMillis()),
        )
    }

    private fun renderRuleStatus(active: ActiveStudySession?) {
        if (active == null) {
            tvRuleStatus.setText(R.string.rule_status_idle)
            return
        }
        val now = System.currentTimeMillis()
        val durationMs = repository.focusBlockDurationMs(active, now)
        val decision = repository.evaluateBreak(active, durationMs, now)
        val state = when {
            StudySessionClock.isOnBreak(active, now) -> "ĐANG NGHỈ"
            durationMs < 30 * 60_000L -> "ĐANG THEO DÕI"
            decision.promptSuppressionReason == BreakPromptEvent.SUPPRESSION_COOLDOWN -> "ĐÃ TẠM HOÃN"
            decision.shouldPrompt -> "ĐỀ XUẤT NGHỈ"
            else -> "TIẾP TỤC HỌC"
        }
        val reasons = decision.reasonCodes.ifEmpty { listOf("CHƯA CÓ RULE KHỚP") }.joinToString("\n")
        tvRuleStatus.text = getString(R.string.rule_status_value, state, reasons)
    }

    private fun renderPostureStatus(active: ActiveStudySession?) {
        tvPostureStatus.text = if (active == null) {
            getString(R.string.posture_status_idle)
        } else if (active.postureSummaries.isEmpty()) {
            getString(R.string.posture_status_unavailable)
        } else if (StudySessionClock.isOnBreak(active, System.currentTimeMillis())) {
            active.postureSummaries.maxByOrNull(PostureStateSummary::totalDurationMs)
                ?.let(PostureRecommendations::advice)
                ?: getString(R.string.posture_status_no_advice)
        } else {
            getString(R.string.posture_status_recorded, active.postureSummaries.sumOf { it.episodeCount })
        }
    }

    private fun renderPostureRuntimeStatus(active: ActiveStudySession?) {
        tvPostureRuntimeStatus.text = if (active == null) {
            getString(R.string.posture_runtime_idle)
        } else {
            getString(R.string.posture_runtime_waiting)
        }
    }

    private fun renderHistory() {
        val sessions = repository.realSessions()
        val todayMinutes = repository.todayTotalMinutes(sessions)
        tvTodayTotal.text = getString(R.string.today_total, formatMinutes(todayMinutes))
        weeklyChart.submit(repository.lastSevenDays(sessions))
        val totalMinutes = sessions.sumOf { it.durationMinutes }
        val accepted = sessions.sumOf { it.breakCount }
        val prompted = sessions.sumOf { it.breakReminderCount }
        val summary = resources.getQuantityString(
            R.plurals.stats_summary,
            sessions.size,
            sessions.size,
            formatMinutes(totalMinutes),
            accepted,
            prompted,
        )
        tvStats.text = summary
        tvRecent.text = if (sessions.isEmpty()) {
            "Chưa có lịch sử. Hãy bắt đầu một phiên học."
        } else {
            sessions.take(3).joinToString("\n") { session ->
                val marker = when {
                    session.breakCount > 0 -> "đã nghỉ ${session.breakCount} lần"
                    session.accepted == true -> "đã nghỉ"
                    session.accepted == false -> "đã hoãn"
                    else -> "không nhắc"
                }
                "${DATE_FORMAT.format(Date(session.startTimeMs))} • ${session.durationMinutes}p • tập trung ${session.focusScore}/5 • $marker"
            }
        }
    }

    private fun prepareMotionFallbackCollector() {
        accCollector = runCatching {
            AccCollector(
                context = this,
                onMotionWindowReady = { metrics ->
                    repository.activeSession()?.sessionId?.let { sessionId ->
                        repository.updateActiveMotion(sessionId, metrics)
                        repository.updateActiveRuleActivity(
                            sessionId,
                            activityThresholdCalibrator.classify(sessionId, metrics),
                            metrics.observedAtMs,
                        )
                    }
                    renderSessionConfidence(repository.activeSession())
                },
            )
        }.getOrNull()
    }

    private fun updateSensorCollection() {
        val active = repository.activeSession()
        accCollector?.stop()
        isCollecting = false
        val servicePermissionGranted =
            checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        val studyingActive = active?.takeIf { isCurrentlyStudying(it) }
        if (studyingActive != null && servicePermissionGranted) {
            SessionSensorService.start(this)
        } else if (studyingActive != null && isResumed) {
            isCollecting = accCollector?.start(studyingActive.startTimeMs) == true
        } else {
            SessionSensorService.stop(this)
        }
    }

    private fun stopSensorCollection() {
        if (isCollecting) accCollector?.stop()
        isCollecting = false
        SessionSensorService.stop(this)
    }

    private fun requestSessionPermissionsIfNeeded() {
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
            if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.BODY_SENSORS)
            }
            if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_SESSION_PERMISSIONS)
    }

    private fun syncStudyDnd(sessionActive: Boolean) {
        if (!sessionActive) {
            StudyDndController.disable(this)
            dndAccessRequested = false
            return
        }
        if (StudyDndController.enable(this)) {
        } else if (isResumed && !dndAccessRequested) {
            dndAccessRequested = true
            StudyDndController.requestAccess(this)
        }
    }

    private fun maybeShowBreakEndChoice() {
        if (!isResumed || breakChoiceVisible) return
        val now = System.currentTimeMillis()
        val active = repository.activeSession() ?: return
        val pending = active.pendingReminder
        if (pending?.kind != PendingReminderKind.BREAK_ENDED) {
            if (active.breakStartedAtMs == null || StudySessionClock.breakRemainingMs(active, now) > 0L) return
            sendReminderAction(
                BreakReminderScheduler.ACTION_BREAK_COMPLETE,
                active.sessionId,
                null,
            )
            return
        }

        syncStudyDnd(false)
        stopSensorCollection()
        breakChoiceVisible = true
        val dialog = AlertDialog.Builder(this)
            .setTitle("Đã hết thời gian nghỉ")
            .setMessage("Bạn muốn tiếp tục học hay nghỉ thêm?")
            .setPositiveButton("Tiếp tục học") { _, _ ->
                sendReminderAction(
                    BreakReminderScheduler.ACTION_RESUME_STUDY,
                    active.sessionId,
                    pending.eventId,
                )
            }
            .setNegativeButton("Nghỉ thêm 5 phút") { _, _ ->
                sendReminderAction(
                    BreakReminderScheduler.ACTION_EXTEND_BREAK_5,
                    active.sessionId,
                    pending.eventId,
                )
            }
            .setNeutralButton("Nghỉ thêm 10 phút") { _, _ ->
                sendReminderAction(
                    BreakReminderScheduler.ACTION_EXTEND_BREAK_10,
                    active.sessionId,
                    pending.eventId,
                )
            }
            .setCancelable(false)
            .create()
        dialog.setOnDismissListener { breakChoiceVisible = false }
        dialog.show()
        renderLiveState()
    }

    private fun resumeAfterBreakChoice(sessionId: String) {
        val resumed = repository.resumeStudyAfterBreak(sessionId, System.currentTimeMillis()) ?: return
        syncStudyDnd(true)
        BreakReminderScheduler.schedule(this, resumed, repository.cooldownUntilMs())
        updateSensorCollection()
        renderAll()
        Toast.makeText(this, "Đã tiếp tục phiên học.", Toast.LENGTH_SHORT).show()
    }

    private fun extendBreakChoice(sessionId: String, minutes: Int) {
        val extended = repository.extendBreak(
            sessionId = sessionId,
            nowMs = System.currentTimeMillis(),
            durationMs = minutes * 60_000L,
        ) ?: return
        syncStudyDnd(false)
        stopSensorCollection()
        BreakReminderScheduler.schedule(this, extended, repository.cooldownUntilMs())
        renderAll()
        Toast.makeText(this, "Đã nghỉ thêm $minutes phút.", Toast.LENGTH_SHORT).show()
    }

    private fun sendReminderAction(action: String, sessionId: String, eventId: String?) {
        sendBroadcast(
            Intent(this, BreakReminderReceiver::class.java)
                .setAction(action)
                .putExtra(BreakReminderScheduler.EXTRA_SESSION_ID, sessionId)
                .apply {
                    if (eventId != null) putExtra(BreakReminderScheduler.EXTRA_EVENT_ID, eventId)
                }
        )
        uiHandler.postDelayed(
            {
                renderAll()
                syncStudyDnd(isCurrentlyStudying(repository.activeSession()))
                updateSensorCollection()
            },
            250L,
        )
    }

    private fun isCurrentlyStudying(active: ActiveStudySession?): Boolean =
        active != null && !StudySessionClock.isOnBreak(active, System.currentTimeMillis())

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_SESSION_PERMISSIONS) updateSensorCollection()
    }

    private fun formatDuration(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = totalSeconds % 3_600L / 60L
        val seconds = totalSeconds % 60L
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    private fun formatShortDuration(milliseconds: Long): String {
        val totalSeconds = ceil(milliseconds / 1_000.0).toLong()
        return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun formatMinutes(minutes: Int): String =
        if (minutes < 60) "$minutes phút" else "${minutes / 60}g ${minutes % 60}p"

    companion object {
        private const val HIDDEN_SUBJECT = "Không áp dụng"
        private const val AUTO_FOCUS_SCORE = 3
        private const val REQUEST_SESSION_PERMISSIONS = 2201
        private val DATE_FORMAT = SimpleDateFormat("dd/MM", Locale("vi", "VN"))
    }
}
