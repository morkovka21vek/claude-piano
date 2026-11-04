package com.example.pianoteacher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.max

const val BLUE = 0xFF2F6FDB.toInt()
const val ORANGE = 0xFFE8831A.toInt()
const val GREEN = 0xFF2FB44A.toInt()
const val RED = 0xFFE03A3A.toInt()

/** Нотный стан (скрипичный + басовый ключ). */
class StaffView(ctx: Context) : View(ctx) {
    var steps: List<Step> = emptyList()
    var index = 0
    var showFingers = true
    var hideNotes = false

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt(); strokeWidth = 1.5f * d; style = Paint.Style.STROKE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val oval = RectF()

    // y по диатонической позиции: линии скрипичного 30..38, басового 18..26
    private fun yOf(dia: Int, refY: Float, half: Float) = refY - (dia - 30) * half

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFFFFFDF6.toInt())
        val half = height / 34f
        val refY = 16 * half
        val left = 12 * d
        val right = width - 8 * d

        for (k in 0 until 5) {
            c.drawLine(left, yOf(30 + 2 * k, refY, half), right, yOf(30 + 2 * k, refY, half), line)
            c.drawLine(left, yOf(18 + 2 * k, refY, half), right, yOf(18 + 2 * k, refY, half), line)
        }
        c.drawLine(left, yOf(38, refY, half), left, yOf(18, refY, half), line)

        text.color = 0xFF555555.toInt(); text.textSize = 5 * half
        c.drawText("G", left + 22 * d, yOf(32, refY, half) + 1.7f * half, text)
        c.drawText("F", left + 22 * d, yOf(24, refY, half) + 1.7f * half, text)

        if (steps.isEmpty()) {
            text.color = 0xFF888888.toInt(); text.textSize = 18 * d
            c.drawText("Откройте MIDI-файл (кнопка «Открыть MIDI»)", width / 2f, height / 2f, text)
            return
        }

        val x0 = 110 * d
        val sp = 62 * d
        fill.style = Paint.Style.FILL
        fill.color = 0x33FFC107
        c.drawRect(x0 - sp / 2, 0f, x0 + sp / 2, height.toFloat(), fill)

        if (hideNotes) {
            text.color = 0xFF888888.toInt(); text.textSize = 16 * d
            c.drawText("Играйте по памяти", width / 2f + 40 * d, height / 2f, text)
            return
        }

        for (i in max(0, index - 1) until steps.size) {
            val x = x0 + (i - index) * sp
            if (x > width + sp) break
            val col = if (i < index) 0xFF9E9E9E.toInt() else 0xFF111111.toInt()
            for (n in steps[i].notes) drawNote(c, n, x, half, refY, col)
        }
    }

    private fun drawNote(c: Canvas, n: Note, x: Float, half: Float, refY: Float, col: Int) {
        val cy = yOf(n.dia, refY, half)
        val rx = 1.45f * half
        val ry = 1.0f * half
        line.color = 0xFF333333.toInt()
        fun ledger(dia: Int) {
            val ly = yOf(dia, refY, half)
            c.drawLine(x - rx * 1.6f, ly, x + rx * 1.6f, ly, line)
        }
        if (n.dia >= 40) { var dd = 40; while (dd <= n.dia) { ledger(dd); dd += 2 } }
        if (n.dia <= 16) { var dd = 16; while (dd >= n.dia) { ledger(dd); dd -= 2 } }
        if (n.dia == 28) ledger(28)

        oval.set(x - rx, cy - ry, x + rx, cy + ry)
        fill.color = col
        fill.strokeWidth = 1.6f * d
        fill.style = if (n.durBeats >= 2.0) Paint.Style.STROKE else Paint.Style.FILL
        c.drawOval(oval, fill)
        if (n.durBeats < 4.0) {
            fill.style = Paint.Style.STROKE
            if (n.dia >= 34) c.drawLine(x - rx, cy, x - rx, cy + 7 * half, fill)
            else c.drawLine(x + rx, cy, x + rx, cy - 7 * half, fill)
        }
        fill.style = Paint.Style.FILL

        if (n.acc != 0) {
            text.color = col; text.textSize = 3.2f * half
            c.drawText(if (n.acc > 0) "#" else "b", x - rx - 1.4f * half, cy + 1.0f * half, text)
        }
        if (showFingers && n.finger > 0) {
            text.textSize = 2.6f * half
            text.color = if (n.hand == 2) ORANGE else BLUE
            val ty = if (n.hand == 2) cy + 1.6f * half + text.textSize * 0.8f else cy - 1.6f * half
            c.drawText(n.finger.toString(), x, ty, text)
        }
    }
}

/** Клавиатура 88 клавиш (A0..C8). */
class KeyboardView(ctx: Context) : View(ctx) {
    var held: Set<Int> = emptySet()
    var wrong: Set<Int> = emptySet()
    var targets: Map<Int, Note> = emptyMap()
    var done: Set<Int> = emptySet()
    var showFingers = true

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF444444.toInt(); strokeWidth = 1f * d
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }

    private fun isBlack(p: Int) = when (p % 12) { 1, 3, 6, 8, 10 -> true; else -> false }

    private fun keyColor(p: Int, base: Int): Int {
        if (p in held) return if (p in wrong) RED else GREEN
        if (p in done) return 0xFF9ADFA8.toInt()
        val t = targets[p]
        if (t != null) return if (t.hand == 2) ORANGE else BLUE
        return base
    }

    private fun label(c: Canvas, p: Int, cx: Float, by: Float, black: Boolean) {
        val t = targets[p]
        if (showFingers && t != null && t.finger > 0 && p !in held) {
            text.color = Color.WHITE; text.textSize = 16 * d
            c.drawText(t.finger.toString(), cx, by, text)
        } else if (p in 21..23) {
            text.color = if (black) Color.WHITE else 0xFF777777.toInt()
            text.textSize = 11 * d
            c.drawText(when (p) { 21 -> "L"; 22 -> "W"; else -> "R" }, cx, by, text)
        }
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val ww = width / 52f
        val bw = ww * 0.62f
        val bh = h * 0.62f
        var wi = 0
        for (p in 21..108) {
            if (!isBlack(p)) {
                val x = wi * ww
                fill.color = keyColor(p, Color.WHITE)
                c.drawRect(x, 0f, x + ww, h, fill)
                c.drawRect(x, 0f, x + ww, h, stroke)
                label(c, p, x + ww / 2, h - 8 * d, false)
                wi++
            }
        }
        wi = 0
        for (p in 21..108) {
            if (isBlack(p)) {
                val x = wi * ww - bw / 2
                fill.color = keyColor(p, 0xFF222222.toInt())
                c.drawRect(x, 0f, x + bw, bh, fill)
                label(c, p, x + bw / 2, bh - 8 * d, true)
            } else wi++
        }
    }
}
