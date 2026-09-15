// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
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
import android.os.SystemClock
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
    private lateinit var sessionController: SessionController
    private lateinit var sessionClock: SessionClock

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
    private lateinit var tvYawnStatus: TextView
    private lateinit var tvTodayTotal: TextView
    private lateinit var weeklyChart: WeeklyStudyChart
    private lateinit var tvStats: TextView
    private lateinit var tvRecent: TextView
    private lateinit var tvPostureRuntimeStatus: TextView
    private lateinit var tvSyncCompatibility: TextView
    private lateinit var tvReminderReadiness: TextView
    private lateinit var btnReminderSettings: Button
    private lateinit var btnTestReminder: Button
    private lateinit var btnCheckIn: Button
    private lateinit var btnTaskBoundary: Button
    private lateinit var btnStartBreakNow: Button
    private lateinit var btnPauseResume: Button
    private lateinit var reminderCard: View
    private lateinit var tvReminderCardTitle: TextView
    private lateinit var tvReminderCardMessage: TextView
    private lateinit var btnReminderCardBreak: Button
    private lateinit var btnReminderCardCheckIn: Button
    private lateinit var btnReminderCardDefer: Button
    private lateinit var btnReminderCardExtend10: Button
    private lateinit var btnReminderCardFinish: Button

    private var accCollector: AccCollector? = null
    private var isCollecting = false
    private var isResumed = false
    private var promptVisible = false
    private var reviewVisible = false
    private var reportVisible = false
    private var recoveryVisible = false
    private var dndAccessRequested = false
    private var suggestedCheckInSource: CheckInSource? = null
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
            if (active?.timeline?.let {
                    it.state != SessionState.RECOVERY_REQUIRED &&
                        SystemClock.elapsedRealtime() - it.checkpointAt.elapsedMs >= 30_000L
                } == true
            ) {
                repository.checkpointActiveSession()
            }
            renderSessionConfidence(active)
            renderRuleStatus(active)
            renderPostureStatus(active)
            renderYawnStatus(active)
            renderPostureRuntimeStatus(active)
            renderSyncCompatibility(active)
            maybeShowForegroundBreakPrompt()
            uiHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchRetentionWorker.schedule(this)
        setContentView(R.layout.activity_main)
        sessionClock = AndroidSessionClock(this)
        repository = StudySessionRepository(this, sessionClock)
        sessionController = DefaultSessionController(repository)
        repository.reconcileActiveSession()
        bindViews()
        bindActions()
        prepareMotionFallbackCollector()
        renderAll()
        syncStudyDnd(isCurrentlyStudying(repository.activeSession()))
        uiHandler.post { maybeShowRecoveryChoice() }
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        BreakAlertChannels.ensureStandard(this)
        BreakAlertChannels.ensurePriorityIfAllowed(this)
        renderAll()
        val active = repository.activeSession()
        if (active?.timeline?.let {
                it.state != SessionState.RECOVERY_REQUIRED &&
                    SystemClock.elapsedRealtime() - it.checkpointAt.elapsedMs >= 30_000L
            } == true
        ) {
            repository.checkpointActiveSession()
        }
        syncStudyDnd(isCurrentlyStudying(active))
        if (active != null) {
            BreakReminderScheduler.schedule(this, active, repository.cooldownUntilMs())
        }
        updateSensorCollection()
        uiHandler.removeCallbacks(ticker)
        uiHandler.post(ticker)
        uiHandler.postDelayed({ maybeShowPendingReview() }, 300L)
        uiHandler.post { maybeShowRecoveryChoice() }
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
        tvYawnStatus = findViewById(R.id.tv_yawn_status)
        tvTodayTotal = findViewById(R.id.tv_today_total)
        weeklyChart = findViewById(R.id.weekly_chart)
        tvStats = findViewById(R.id.tv_stats)
        tvRecent = findViewById(R.id.tv_recent)
        tvPostureRuntimeStatus = findViewById(R.id.tv_posture_runtime_status)
        tvSyncCompatibility = findViewById(R.id.tv_sync_compatibility)
        tvReminderReadiness = findViewById(R.id.tv_reminder_readiness)
        btnReminderSettings = findViewById(R.id.btn_reminder_settings)
        btnTestReminder = findViewById(R.id.btn_test_reminder)
        btnCheckIn = findViewById(R.id.btn_check_in)
        btnTaskBoundary = findViewById(R.id.btn_task_boundary)
        btnStartBreakNow = findViewById(R.id.btn_start_break_now)
        btnPauseResume = findViewById(R.id.btn_pause_resume)
        reminderCard = findViewById(R.id.reminder_card)
        tvReminderCardTitle = findViewById(R.id.tv_reminder_card_title)
        tvReminderCardMessage = findViewById(R.id.tv_reminder_card_message)
        btnReminderCardBreak = findViewById(R.id.btn_reminder_card_break)
        btnReminderCardCheckIn = findViewById(R.id.btn_reminder_card_check_in)
        btnReminderCardDefer = findViewById(R.id.btn_reminder_card_defer)
        btnReminderCardExtend10 = findViewById(R.id.btn_reminder_card_extend_10)
        btnReminderCardFinish = findViewById(R.id.btn_reminder_card_finish)
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
        btnCheckIn.setOnClickListener {
            showVoluntaryCheckIn(suggestedCheckInSource ?: CheckInSource.USER_REQUEST)
        }
        btnTaskBoundary.setOnClickListener { showVoluntaryCheckIn(CheckInSource.TASK_BOUNDARY) }
        btnStartBreakNow.setOnClickListener { startVoluntaryBreak() }
        btnPauseResume.setOnClickListener { togglePause() }
        btnReminderCardBreak.setOnClickListener { sendCurrentReminderPrimaryAction() }
        btnReminderCardCheckIn.setOnClickListener { showVoluntaryCheckIn(CheckInSource.BEFORE_BREAK) }
        btnReminderCardDefer.setOnClickListener { sendCurrentReminderSecondaryAction() }
        btnReminderCardExtend10.setOnClickListener {
            sendCurrentBreakEndAction(BreakReminderScheduler.ACTION_EXTEND_BREAK_10)
        }
        btnReminderCardFinish.setOnClickListener { finishSession() }
    }

    private fun startSession() {
        val at = sessionClock.now()
        val sessionId = UUID.randomUUID().toString()
        val active = ActiveStudySession(
            sessionId = sessionId,
            startTimeMs = at.wallMs,
            subject = HIDDEN_SUBJECT,
            taskType = taskTypes[taskIndex],
            focusScore = focusScore,
            fatigueScore = fatigueScore,
            breakTargetMinutes = repository.recommendedBreakTargetMinutes(),
            timeline = SessionTimelineSnapshot(
                sessionId = sessionId,
                revision = 0L,
                state = SessionState.STUDYING,
                blockId = "block-0",
                enteredAt = at,
            ),
        )
        val started = sessionController.dispatch(SessionCommand.Start("start:$sessionId", at, active))
        if (!started.applied) {
            Toast.makeText(this, "Không thể bắt đầu vì đang có phiên khác.", Toast.LENGTH_LONG).show()
            return
        }
        suggestedCheckInSource = null
        repository.recordCheckIn(
            active.sessionId,
            CheckInSource.SESSION_START,
            focus = active.focusScore,
            fatigue = active.fatigueScore,
            at = at,
            checkInId = "${active.sessionId}:start",
        )
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
            .setNeutralButton("Check-in trước") { _, _ -> showVoluntaryCheckIn(CheckInSource.SESSION_END) }
            .setNegativeButton("Tiếp tục", null)
            .show()
    }

    private fun finishSession() {
        val active = repository.activeSession() ?: return
        val at = sessionClock.now()
        val result = sessionController.dispatch(
            SessionCommand.Finish("finish:${active.sessionId}:${at.elapsedMs}", at, active.sessionId, active.focusBlockId)
        )
        if (!result.applied) return
        suggestedCheckInSource = null
        val completed = repository.sessions().firstOrNull { it.sessionId == active.sessionId } ?: return
        BreakReminderScheduler.cancel(this)
        syncStudyDnd(false)
        stopSensorCollection()
        renderAll()
        reportVisible = true
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.session_report_title)
            .setView(SessionReportViewFactory.create(this, completed))
            .setPositiveButton(R.string.report_close, null)
            .create()
        dialog.setOnDismissListener {
            reportVisible = false
            uiHandler.post { maybeShowPendingReview() }
        }
        dialog.show()
    }

    private fun confirmCancelSession() {
        AlertDialog.Builder(this)
            .setTitle("Hủy phiên đang học?")
            .setMessage("Phiên này sẽ không được ghi vào lịch sử.")
            .setPositiveButton("Hủy phiên") { _, _ ->
                val active = repository.activeSession() ?: return@setPositiveButton
                val at = sessionClock.now()
                sessionController.dispatch(
                    SessionCommand.Cancel("cancel:${active.sessionId}:${at.elapsedMs}", at, active.sessionId, active.focusBlockId)
                )
                suggestedCheckInSource = null
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

    private fun startVoluntaryBreak() {
        val active = repository.activeSession() ?: return
        if (StudySessionClock.isOnBreak(active, System.currentTimeMillis())) return
        val at = sessionClock.now()
        val result = sessionController.dispatch(
            SessionCommand.StartBreak(
                commandId = "voluntary-break:${UUID.randomUUID()}",
                at = at,
                sessionId = active.sessionId,
                blockId = active.focusBlockId,
                activity = BreakActivityType.FULL_BREAK,
            )
        )
        if (!result.applied) return
        val resting = repository.activeSession() ?: return
        BreakReminderScheduler.cancel(this)
        syncStudyDnd(false)
        stopSensorCollection()
        BreakReminderScheduler.schedule(this, resting, repository.cooldownUntilMs())
        renderAll()
    }

    private fun togglePause() {
        val active = repository.activeSession() ?: return
        val at = sessionClock.now()
        val result = if (StudySessionClock.isPaused(active)) {
            sessionController.dispatch(
                SessionCommand.ResumePaused("resume-pause:${active.sessionId}:${at.elapsedMs}", at, active.sessionId, active.focusBlockId)
            )
        } else {
            sessionController.dispatch(
                SessionCommand.Pause("pause:${active.sessionId}:${at.elapsedMs}", at, active.sessionId, active.focusBlockId)
            )
        }
        if (!result.applied) return
        val updated = repository.activeSession() ?: return
        if (StudySessionClock.isPaused(updated)) {
            BreakReminderScheduler.cancel(this)
            syncStudyDnd(false)
            stopSensorCollection()
        } else {
            syncStudyDnd(true)
            BreakReminderScheduler.schedule(this, updated, repository.cooldownUntilMs())
            updateSensorCollection()
        }
        renderAll()
    }

    private fun showVoluntaryCheckIn(source: CheckInSource) {
        val active = repository.activeSession() ?: return
        val fatigueChoices = arrayOf("Bỏ qua mức mệt") + (1..10).map { "Mệt $it/10" }
        AlertDialog.Builder(this)
            .setTitle("Bạn thấy mệt mức nào?")
            .setItems(fatigueChoices) { _, fatigueIndex ->
                val fatigue = fatigueIndex.takeIf { it > 0 }
                showFocusCheckIn(active.sessionId, fatigue, source)
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showFocusCheckIn(sessionId: String, fatigue: Int?, source: CheckInSource) {
        val focusChoices = arrayOf("Bỏ qua mức tập trung") + (1..5).map { "Tập trung $it/5" }
        AlertDialog.Builder(this)
            .setTitle("Mức tập trung hiện tại?")
            .setItems(focusChoices) { _, focusIndex ->
                val focus = focusIndex.takeIf { it > 0 }
                if (focus == null && fatigue == null) return@setItems
                if (repository.recordCheckIn(sessionId, source, focus, fatigue)) {
                    if (suggestedCheckInSource == source) suggestedCheckInSource = null
                    Toast.makeText(this, "Đã lưu tự đánh giá; luật mới chỉ chạy shadow.", Toast.LENGTH_LONG).show()
                }
                renderAll()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun maybeShowForegroundBreakPrompt() {
        if (!isResumed) return
        val active = repository.activeSession() ?: return
        if (active.timeline?.state == SessionState.RECOVERY_REQUIRED) return
        val now = System.currentTimeMillis()
        if (StudySessionClock.isOnBreak(active, now)) return
        val pending = active.pendingReminder
        if (pending?.kind == PendingReminderKind.BREAK_SUGGESTION) {
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

    private fun maybeShowRecoveryChoice() {
        if (!isResumed || recoveryVisible) return
        val active = repository.activeSession() ?: return
        if (active.timeline?.state != SessionState.RECOVERY_REQUIRED) return
        recoveryVisible = true
        val dialog = AlertDialog.Builder(this)
            .setTitle("Cần xác nhận phiên học")
            .setMessage("Đồng hồ đã khởi động lại hoặc thiếu mốc thời gian. Khoảng chưa xác nhận không được tính là học hay nghỉ.")
            .setPositiveButton("Học tiếp từ bây giờ") { _, _ ->
                val at = sessionClock.now()
                val result = sessionController.dispatch(
                    SessionCommand.Recover("recover:${active.sessionId}:${at.elapsedMs}", at, active.sessionId, active.focusBlockId)
                )
                repository.activeSession()?.takeIf { result.applied }?.let {
                    BreakReminderScheduler.schedule(this, it, repository.cooldownUntilMs())
                    updateSensorCollection()
                }
                renderAll()
            }
            .setNegativeButton("Kết thúc tại mốc đã lưu") { _, _ -> finishSession() }
            .setCancelable(false)
            .create()
        dialog.setOnDismissListener { recoveryVisible = false }
        dialog.show()
    }

    private fun maybeShowPendingReview() {
        if (!isResumed || promptVisible || reviewVisible || reportVisible) return
        val session = repository.pendingReviewSession() ?: return
        reviewVisible = true
        val timingChoices = arrayOf("Quá sớm", "Vừa lúc", "Quá muộn", "Bỏ qua")
        val dialog = AlertDialog.Builder(this)
            .setTitle("Đánh giá thời điểm nhắc")
            .setItems(timingChoices) { _, index ->
                when (index) {
                    0 -> if (!repository.recordPromptTimingFeedback(session.sessionId, PromptTimingFeedback.TOO_EARLY)) repository.clearPendingReview()
                    1 -> if (!repository.recordPromptTimingFeedback(session.sessionId, PromptTimingFeedback.ABOUT_RIGHT)) repository.clearPendingReview()
                    2 -> if (!repository.recordPromptTimingFeedback(session.sessionId, PromptTimingFeedback.TOO_LATE)) repository.clearPendingReview()
                    else -> repository.clearPendingReview()
                }
                renderHistory()
            }
            .create()
        dialog.setOnCancelListener { repository.clearPendingReview() }
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
                    val at = sessionClock.now().copy(wallMs = now)
                    val result = sessionController.dispatch(
                        SessionCommand.DeferReminder(
                            commandId = "defer:$eventId",
                            at = at,
                            sessionId = sessionId,
                            blockId = active.focusBlockId,
                            reminderId = eventId,
                            cooldownUntilWallMs = cooldownUntil,
                            declineReasonCode = reasons[index].first,
                            sessionDeferReason = reasons[index].second,
                        )
                    )
                    val updated = repository.activeSession()?.takeIf { result.applied || result.duplicate } ?: return@setItems
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
        renderYawnStatus(active)
        renderHistory()
        renderPostureRuntimeStatus(active)
        renderSyncCompatibility(active)
        renderReminderReadiness()
        renderReminderCard(active)
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
        val active = repository.activeSession()
        val studying = active != null && !StudySessionClock.isOnBreak(active, System.currentTimeMillis())
        btnCheckIn.visibility = if (active != null) View.VISIBLE else View.GONE
        btnCheckIn.text = if (suggestedCheckInSource == CheckInSource.AFTER_BREAK) {
            "Check-in sau nghỉ (tùy chọn)"
        } else {
            "Check-in tự nguyện"
        }
        btnTaskBoundary.visibility = if (studying && active?.let(StudySessionClock::isPaused) == false) View.VISIBLE else View.GONE
        btnStartBreakNow.visibility = if (studying && active?.let(StudySessionClock::isPaused) == false) View.VISIBLE else View.GONE
        btnPauseResume.visibility = if (studying) View.VISIBLE else View.GONE
        btnPauseResume.text = if (active?.let(StudySessionClock::isPaused) == true) "Tiếp tục học" else "Tạm dừng"
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
            val breakRemainingMs = repository.breakRemainingMs(active)
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
        renderReminderCard(active)
    }

    private fun renderReminderCard(active: ActiveStudySession?) {
        val reminder = active?.pendingReminder
        reminderCard.visibility = if (reminder == null) View.GONE else View.VISIBLE
        tvReminderCardMessage.text = reminder?.message.orEmpty()
        val breakEnded = reminder?.kind == PendingReminderKind.BREAK_ENDED
        tvReminderCardTitle.text = if (breakEnded) "HẾT GIỜ NGHỈ" else "ĐỀ NGHỊ NGHỈ"
        btnReminderCardBreak.text = if (breakEnded) "Tiếp tục học" else "Nghỉ 5 phút"
        btnReminderCardDefer.text = if (breakEnded) "Nghỉ thêm 5 phút" else "Để sau 20 phút"
        btnReminderCardCheckIn.visibility = if (breakEnded) View.GONE else View.VISIBLE
        btnReminderCardExtend10.visibility = if (breakEnded) View.VISIBLE else View.GONE
        btnReminderCardFinish.visibility = if (breakEnded) View.VISIBLE else View.GONE
    }

    private fun sendCurrentReminderPrimaryAction() {
        val active = repository.activeSession() ?: return
        val reminder = active.pendingReminder ?: return
        val action = if (reminder.kind == PendingReminderKind.BREAK_ENDED) {
            BreakReminderScheduler.ACTION_RESUME_STUDY
        } else BreakReminderScheduler.ACTION_ACCEPT
        sendReminderAction(action, active.sessionId, reminder.eventId)
    }

    private fun sendCurrentReminderSecondaryAction() {
        val active = repository.activeSession() ?: return
        val reminder = active.pendingReminder ?: return
        val action = if (reminder.kind == PendingReminderKind.BREAK_ENDED) {
            BreakReminderScheduler.ACTION_EXTEND_BREAK_5
        } else BreakReminderScheduler.ACTION_DEFER
        sendReminderAction(action, active.sessionId, reminder.eventId)
    }

    private fun sendCurrentBreakEndAction(action: String) {
        val active = repository.activeSession() ?: return
        val reminder = active.pendingReminder?.takeIf { it.kind == PendingReminderKind.BREAK_ENDED } ?: return
        sendReminderAction(action, active.sessionId, reminder.eventId)
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
            active.timeline?.state == SessionState.RECOVERY_REQUIRED -> "CẦN PHỤC HỒI"
            StudySessionClock.isOnBreak(active, now) -> "ĐANG NGHỈ"
            StudySessionClock.isPaused(active) -> "TẠM DỪNG"
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

    private fun renderYawnStatus(active: ActiveStudySession?) {
        if (active == null) {
            tvYawnStatus.setText(R.string.yawn_status_idle)
            return
        }
        val detection = YawnRuntimeStore.snapshot
        tvYawnStatus.text = when {
            detection?.advisory == true -> getString(
                R.string.yawn_status_advisory,
                detection.eventsInWindow,
            )
            detection?.state == YawnState.YAWNING -> getString(
                R.string.yawn_status_detected,
                detection.totalCount,
            )
            detection?.state == YawnState.CALIBRATING -> getString(
                R.string.yawn_status_calibrating,
                detection.calibrationProgress,
                detection.calibrationRequired,
            )
            else -> getString(R.string.yawn_status_tracking, active.yawnCount)
        }
    }

    private fun renderPostureRuntimeStatus(active: ActiveStudySession?) {
        if (active == null) {
            tvPostureRuntimeStatus.setText(R.string.posture_runtime_idle)
            return
        }
        val snapshot = PostureRuntimeStore.snapshot
        val phase = when (snapshot.phase) {
            PostureRuntimePhase.DISCONNECTED -> "MẤT KẾT NỐI"
            PostureRuntimePhase.CONNECTING -> "ĐANG KẾT NỐI"
            PostureRuntimePhase.BONDING -> "ĐANG GHÉP ĐÔI"
            PostureRuntimePhase.CALIBRATING -> "ĐANG HIỆU CHỈNH"
            PostureRuntimePhase.LIVE -> "LIVE"
            PostureRuntimePhase.STALE -> "STALE"
            PostureRuntimePhase.UNAVAILABLE -> "KHÔNG TƯƠNG THÍCH"
        }
        val link = buildList {
            snapshot.mtu?.let { add("MTU $it") }
            snapshot.notificationRateHz?.let { add("%.1f Hz".format(Locale.US, it)) }
            add(
                when (snapshot.source) {
                    PostureSource.NONE -> "nguồn chưa sẵn sàng"
                    PostureSource.BLE_GEOMETRY -> "bbox BLE"
                    PostureSource.MEDIAPIPE_POSE_LITE -> "Pose Lite local"
                },
            )
            add("nhiệt ${snapshot.thermalState.name.lowercase(Locale.US)}")
        }.joinToString(" • ")
        val detail = buildList {
            add(snapshot.detail.ifBlank { "Chưa có chi tiết" })
            if (snapshot.localPosePhase != LocalPosePhase.STOPPED || snapshot.localPoseDetail.isNotBlank()) {
                add("Pose ${snapshot.localPosePhase.name}: ${snapshot.localPoseDetail.ifBlank { "chưa có chi tiết" }}")
            }
        }.joinToString("\n")
        tvPostureRuntimeStatus.text = getString(
            R.string.posture_runtime_value,
            phase,
            detail,
            link.ifBlank { "chưa đo link" },
        )
    }

    private fun renderSyncCompatibility(active: ActiveStudySession?) {
        val coreIncompatible = active != null && PostureRuntimeStore.snapshot.let { snapshot ->
            snapshot.phase == PostureRuntimePhase.UNAVAILABLE &&
                snapshot.detail.contains("không tương thích", ignoreCase = true)
        }
        tvSyncCompatibility.setText(
            when {
                coreIncompatible -> R.string.yawn_sync_incompatible
                active == null -> R.string.yawn_sync_checking
                YawnSyncRuntimeStore.compatibility == YawnSyncCompatibility.V2 -> R.string.yawn_sync_v2
                YawnSyncRuntimeStore.compatibility == YawnSyncCompatibility.LEGACY -> R.string.yawn_sync_legacy
                YawnSyncRuntimeStore.compatibility == YawnSyncCompatibility.INCOMPATIBLE -> R.string.yawn_sync_incompatible
                else -> R.string.yawn_sync_checking
            },
        )
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
                "${DATE_FORMAT.format(Date(session.startTimeMs))} • ${session.durationMinutes}p • tập trung ${session.focusScore}/5 • ngáp ${session.yawnCount} • $marker"
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
                        BreakReminderScheduler.requestImmediateCheck(this)
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
            hasHeartRatePermission() ||
                checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED ||
                hasBluetoothPermissions()
        val studyingActive = active?.takeIf { isCurrentlyStudying(it) }
        if (studyingActive != null && servicePermissionGranted) {
            SessionSensorService.start(this)
        } else if (studyingActive != null && isResumed) {
            isCollecting = accCollector?.start(
                studyingActive.startTimeMs,
                studyingActive.focusBlockId,
                AndroidSessionClock(this).now().bootId,
                studyingActive.sessionId,
            ) == true
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
            val heartRatePermission = if (Build.VERSION.SDK_INT >= ANDROID_16_API) {
                HEART_RATE_PERMISSION
            } else {
                Manifest.permission.BODY_SENSORS
            }
            if (checkSelfPermission(heartRatePermission) != PackageManager.PERMISSION_GRANTED) {
                add(heartRatePermission)
            }
            if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.BLUETOOTH_SCAN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_SESSION_PERMISSIONS)
    }

    private fun hasHeartRatePermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= ANDROID_16_API) {
            HEART_RATE_PERMISSION
        } else {
            Manifest.permission.BODY_SENSORS
        }
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
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
        if (!isResumed) return
        val active = repository.activeSession() ?: return
        val pending = active.pendingReminder
        if (pending?.kind == PendingReminderKind.BREAK_ENDED) return
        if (active.breakStartedAtMs == null || repository.breakRemainingMs(active) > 0L) return
        sendReminderAction(BreakReminderScheduler.ACTION_BREAK_COMPLETE, active.sessionId, null)
    }

    private fun resumeAfterBreakChoice(sessionId: String) {
        val active = repository.activeSession()?.takeIf { it.sessionId == sessionId } ?: return
        val at = sessionClock.now()
        val result = sessionController.dispatch(
            SessionCommand.ResumeAfterBreak(
                "resume-break:${active.pendingReminder?.eventId ?: at.elapsedMs}",
                at,
                sessionId,
                active.focusBlockId,
            )
        )
        if (!result.applied) return
        val resumed = repository.activeSession() ?: return
        syncStudyDnd(true)
        BreakReminderScheduler.schedule(this, resumed, repository.cooldownUntilMs())
        updateSensorCollection()
        renderAll()
        Toast.makeText(this, "Đã tiếp tục phiên học.", Toast.LENGTH_SHORT).show()
        suggestedCheckInSource = CheckInSource.AFTER_BREAK
        renderInputs()
    }

    private fun extendBreakChoice(sessionId: String, minutes: Int) {
        val active = repository.activeSession()?.takeIf { it.sessionId == sessionId } ?: return
        val at = sessionClock.now()
        val result = sessionController.dispatch(
            SessionCommand.ExtendBreak(
                "extend-break:${active.pendingReminder?.eventId ?: at.elapsedMs}:$minutes",
                at,
                sessionId,
                active.focusBlockId,
                minutes * 60_000L,
            )
        )
        if (!result.applied) return
        val extended = repository.activeSession() ?: return
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
                if (action == BreakReminderScheduler.ACTION_RESUME_STUDY) {
                    suggestedCheckInSource = CheckInSource.AFTER_BREAK
                    renderInputs()
                }
            },
            250L,
        )
    }

    private fun isCurrentlyStudying(active: ActiveStudySession?): Boolean =
        active != null && !StudySessionClock.isOnBreak(active, System.currentTimeMillis()) &&
            !StudySessionClock.isPaused(active)

    private fun hasBluetoothPermissions(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)

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
        private const val ANDROID_16_API = 36
        private const val HEART_RATE_PERMISSION = "android.permission.health.READ_HEART_RATE"
        private const val HIDDEN_SUBJECT = "Không áp dụng"
        private const val AUTO_FOCUS_SCORE = 3
        private const val REQUEST_SESSION_PERMISSIONS = 2201
        private val DATE_FORMAT = SimpleDateFormat("dd/MM", Locale("vi", "VN"))
    }
}
