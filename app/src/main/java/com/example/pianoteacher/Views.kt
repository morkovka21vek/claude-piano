package com.example.pianoteacher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

const val BLUE = 0xFF2F6FDB.toInt()
const val ORANGE = 0xFFE8831A.toInt()
const val GREEN = 0xFF2FB44A.toInt()
const val RED = 0xFFE03A3A.toInt()

/**
 * Нотный стан: две системы (скрипичный + басовый ключ) одна над другой.
 * Активная — светлая, с курсором; вторая — тёмная, показывает следующую строку.
 * Когда курсор доходит до конца активной строки, активной становится вторая,
 * а освободившаяся заполняется следующей строкой.
 */
class StaffView(ctx: Context) : View(ctx) {
    var steps: List<Step> = emptyList()
        set(v) { field = v; recalcRange(); invalidate() }
    var index = 0
    var showFingers = true
    var hideNotes = false
    var held: Set<Int> = emptySet()
    var wrong: Set<Int> = emptySet()
    var onSeek: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.4f * d; style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val oval = RectF()

    private var topD = 40
    private var botD = 16
    private var half = 1f
    private var slotH = 1f
    private var leftM = 44 * d
    private var sp = 46 * d
    private var perLine = 0
    private val lineInSlot = intArrayOf(-1, -1)

    private fun recalcRange() {
        var mx = 40
        var mn = 16
        for (s in steps) for (n in s.notes) { mx = max(mx, n.dia + 1); mn = min(mn, n.dia - 1) }
        topD = mx; botD = mn
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFFD9D5C8.toInt())
        if (steps.isEmpty()) {
            text.color = 0xFF666666.toInt(); text.textSize = 18 * d
            c.drawText("Откройте MIDI-файл (кнопка «Открыть MIDI»)", width / 2f, height / 2f, text)
            return
        }
        val rightM = 8 * d
        leftM = 44 * d
        perLine = max(3, ((width - leftM - rightM) / (46 * d)).toInt())
        sp = (width - leftM - rightM) / perLine
        slotH = height / 2f
        half = slotH / (topD - botD + 2)

        val activeLine = index / perLine
        val total = (steps.size + perLine - 1) / perLine
        lineInSlot[activeLine % 2] = activeLine
        lineInSlot[(activeLine + 1) % 2] = if (activeLine + 1 < total) activeLine + 1 else -1
        for (slot in 0..1) drawSystem(c, slot, lineInSlot[slot], slot == activeLine % 2)
    }

    private fun yOf(dia: Int, top: Float) = top + (topD + 1 - dia) * half

    private fun drawSystem(c: Canvas, slot: Int, ln: Int, active: Boolean) {
        val top = slot * slotH
        fill.style = Paint.Style.FILL
        fill.color = if (active) 0xFFFFFDF6.toInt() else 0xFFD9D5C8.toInt()
        c.drawRect(0f, top, width.toFloat(), top + slotH, fill)
        line.color = 0xFF777777.toInt()
        if (slot == 1) c.drawLine(0f, top, width.toFloat(), top, line)
        if (ln < 0) return

        line.color = if (active) 0xFF333333.toInt() else 0xFF8D897D.toInt()
        val right = width - 8 * d
        val left = 12 * d
        for (k in 0 until 5) {
            c.drawLine(left, yOf(30 + 2 * k, top), right, yOf(30 + 2 * k, top), line)
            c.drawLine(left, yOf(18 + 2 * k, top), right, yOf(18 + 2 * k, top), line)
        }
        c.drawLine(left, yOf(38, top), left, yOf(18, top), line)
        text.color = if (active) 0xFF555555.toInt() else 0xFF8D897D.toInt()
        text.textSize = 4.5f * half
        c.drawText("G", left + 14 * d, yOf(32, top) + 1.6f * half, text)
        c.drawText("F", left + 14 * d, yOf(24, top) + 1.6f * half, text)

        val first = ln * perLine
        val last = min(steps.size, first + perLine)

        if (active) {
            val x = leftM + (index - first) * sp
            fill.color = 0x33FFC107
            c.drawRect(x, top, x + sp, top + slotH, fill)
            fill.color = 0xFFE53935.toInt()
            c.drawRect(x, top, x + 3 * d, top + slotH, fill)
        }

        if (hideNotes) {
            if (active) {
                text.color = 0xFF888888.toInt(); text.textSize = 16 * d
                c.drawText("Играйте по памяти", width / 2f, top + slotH / 2f, text)
            }
        } else {
            for (i in first until last) {
                val x = leftM + (i - first + 0.5f) * sp
                val col = when {
                    !active -> 0xFF857F72.toInt()
                    i < index -> 0xFFA5A5A5.toInt()
                    else -> 0xFF111111.toInt()
                }
                for (n in steps[i].notes) drawNote(c, n, x, top, col, false, showFingers && active)
            }
        }

        // нажатые сейчас клавиши — на нотах, в позиции курсора
        if (active && index in first until last) {
            val cx = leftM + (index - first + 0.5f) * sp
            val st = steps[index]
            for (p in held) {
                val ref = st.notes.firstOrNull { it.pitch == p } ?: noteFromMidi(0.0, 1.0, 0, 0, p, 80)
                val bad = p in wrong
                drawNote(c, ref, if (bad) cx + 0.3f * sp else cx, top, if (bad) RED else GREEN, true, false)
            }
        }
    }

    private fun drawNote(c: Canvas, n: Note, x: Float, top: Float, col: Int, overlay: Boolean, fingers: Boolean) {
        val cy = yOf(n.dia, top)
        val rx = 1.45f * half
        val ry = 1.0f * half
        fun ledger(dia: Int) {
            val ly = yOf(dia, top)
            c.drawLine(x - rx * 1.6f, ly, x + rx * 1.6f, ly, line)
        }
        if (n.dia >= 40) { var dd = 40; while (dd <= n.dia) { ledger(dd); dd += 2 } }
        if (n.dia <= 16) { var dd = 16; while (dd >= n.dia) { ledger(dd); dd -= 2 } }
        if (n.dia == 28) ledger(28)

        oval.set(x - rx, cy - ry, x + rx, cy + ry)
        fill.color = col
        fill.strokeWidth = 1.6f * d
        fill.style = if (!overlay && n.durBeats >= 2.0) Paint.Style.STROKE else Paint.Style.FILL
        c.drawOval(oval, fill)
        if (!overlay && n.durBeats < 4.0) {
            fill.style = Paint.Style.STROKE
            if (n.dia >= 34) c.drawLine(x - rx, cy, x - rx, cy + 7 * half, fill)
            else c.drawLine(x + rx, cy, x + rx, cy - 7 * half, fill)
        }
        fill.style = Paint.Style.FILL
        if (n.acc != 0) {
            text.color = col; text.textSize = 3.2f * half
            c.drawText(if (n.acc > 0) "#" else "b", x - rx - 1.4f * half, cy + 1.0f * half, text)
        }
        if (fingers && n.finger > 0) {
            text.textSize = 2.6f * half
            text.color = if (n.hand == 2) ORANGE else BLUE
            val ty = if (n.hand == 2) cy + 1.6f * half + text.textSize * 0.8f else cy - 1.6f * half
            c.drawText(n.finger.toString(), x, ty, text)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (steps.isEmpty() || perLine == 0) return false
        if (e.action == MotionEvent.ACTION_UP) {
            val ln = lineInSlot[if (e.y < height / 2f) 0 else 1]
            if (ln >= 0) {
                val j = ((e.x - leftM) / sp).toInt().coerceIn(0, perLine - 1)
                val i = ln * perLine + j
                if (i < steps.size) onSeek?.invoke(i)
            }
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

/** Клавиатура 88 клавиш (A0..C8). */
class KeyboardView(ctx: Context) : View(ctx) {
    var held: Set<Int> = emptySet()
    var wrong: Set<Int> = emptySet()
    var targets: Map<Int, Note> = emptyMap()
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
        val t = targets[p]
        if (t != null) return if (t.hand == 2) ORANGE else BLUE
        return base
    }

    private fun label(c: Canvas, p: Int, cx: Float, by: Float) {
        val t = targets[p]
        if (showFingers && t != null && t.finger > 0 && p !in held) {
            text.color = Color.WHITE; text.textSize = 16 * d
            c.drawText(t.finger.toString(), cx, by, text)
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
                label(c, p, x + ww / 2, h - 8 * d)
                wi++
            }
        }
        wi = 0
        for (p in 21..108) {
            if (isBlack(p)) {
                val x = wi * ww - bw / 2
                fill.color = keyColor(p, 0xFF222222.toInt())
                c.drawRect(x, 0f, x + bw, bh, fill)
                label(c, p, x + bw / 2, bh - 8 * d)
            } else wi++
        }
    }
}

/** Контур двух рук: подсвечены пальцы, которые нужно использовать. */
class HandsView(ctx: Context) : View(ctx) {
    var targets: Map<Int, Note> = emptyMap()
    var showFingers = true

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val len = floatArrayOf(0f, 0.30f, 0.46f, 0.52f, 0.46f, 0.34f) // по номеру пальца, доли высоты

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFFF4F1E8.toInt())
        val act = HashMap<Int, HashSet<Int>>()
        if (showFingers) for (n in targets.values) if (n.finger in 1..5) act.getOrPut(n.hand) { HashSet() }.add(n.finger)
        val w = width / 2f
        drawHand(c, 0f, w, 2, act[2] ?: emptySet<Int>(), "Левая")
        drawHand(c, w, w, 1, act[1] ?: emptySet<Int>(), "Правая")
    }

    private fun drawHand(c: Canvas, x0: Float, w: Float, hand: Int, active: Set<Int>, name: String) {
        val hh = height.toFloat()
        val hw = w * 0.88f
        val left = x0 + (w - hw) / 2
        val col = hw / 5f
        val fw = col * 0.82f
        val palmTop = hh * 0.55f
        val color = if (hand == 2) ORANGE else BLUE

        r.set(left, palmTop, left + hw, hh * 0.95f)
        p.style = Paint.Style.FILL; p.color = Color.WHITE
        c.drawRoundRect(r, 12 * d, 12 * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = 0xFF777777.toInt()
        c.drawRoundRect(r, 12 * d, 12 * d, p)
        p.style = Paint.Style.FILL; p.color = 0xFF999999.toInt(); p.textAlign = Paint.Align.CENTER
        p.textSize = 11 * d; p.typeface = Typeface.DEFAULT
        c.drawText(name, left + hw / 2, hh * 0.80f, p)

        for (k in 0 until 5) {
            val f = if (hand == 1) k + 1 else 5 - k
            val fl = left + k * col + (col - fw) / 2
            val base = palmTop + (if (f == 1) 0.14f * hh else 0.03f * hh)
            val top = base - len[f] * hh
            r.set(fl, top, fl + fw, base)
            val on = f in active
            p.style = Paint.Style.FILL; p.color = if (on) color else Color.WHITE
            c.drawRoundRect(r, fw / 2, fw / 2, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = if (on) color else 0xFF777777.toInt()
            c.drawRoundRect(r, fw / 2, fw / 2, p)
            p.style = Paint.Style.FILL; p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = fw * 0.6f
            p.color = if (on) Color.WHITE else 0xFF999999.toInt()
            c.drawText(f.toString(), fl + fw / 2, top + fw * 0.95f, p)
        }
    }
}
