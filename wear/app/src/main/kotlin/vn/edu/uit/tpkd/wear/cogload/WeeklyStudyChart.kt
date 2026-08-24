// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** Tiny dependency-free seven-day bar chart designed for a round Wear screen. */
class WeeklyStudyChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(101, 196, 255) }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(37, 52, 68) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textSize = 18f
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f
        textAlign = Paint.Align.CENTER
    }

    private var totals: List<DailyStudyTotal> = emptyList()

    fun submit(values: List<DailyStudyTotal>) {
        totals = values.takeLast(7)
        contentDescription = if (totals.isEmpty()) {
            "Chưa có dữ liệu học"
        } else {
            totals.joinToString(", ") { "${it.label}: ${it.minutes} phút" }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (totals.isEmpty()) return
        val top = paddingTop.toFloat() + 18f
        val bottom = height - paddingBottom.toFloat() - 24f
        val chartHeight = (bottom - top).coerceAtLeast(1f)
        val slotWidth = (width - paddingLeft - paddingRight).toFloat() / totals.size
        val barWidth = slotWidth * 0.52f
        val maxMinutes = max(totals.maxOfOrNull { it.minutes } ?: 0, 1)

        totals.forEachIndexed { index, total ->
            val centerX = paddingLeft + slotWidth * (index + 0.5f)
            val left = centerX - barWidth / 2f
            val right = centerX + barWidth / 2f
            canvas.drawRoundRect(left, top, right, bottom, 5f, 5f, emptyPaint)
            if (total.minutes > 0) {
                val fraction = total.minutes.toFloat() / maxMinutes
                val barTop = bottom - chartHeight * fraction
                canvas.drawRoundRect(left, barTop, right, bottom, 5f, 5f, barPaint)
                canvas.drawText(total.minutes.toString(), centerX, (barTop - 4f).coerceAtLeast(14f), valuePaint)
            }
            canvas.drawText(total.label, centerX, height - paddingBottom.toFloat() - 3f, labelPaint)
        }
    }
}
