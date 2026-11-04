package com.example.pianoteacher

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.math.abs

/** Одна нота. hand: 1 = правая, 2 = левая. finger: 0 = неизвестно, 1..5. */
class Note(
    val beat: Double, val durBeats: Double,
    val startMs: Long, val endMs: Long,
    val pitch: Int, val vel: Int,
    val dia: Int,   // диатоническая позиция на нотном стане (C4 = 28)
    val acc: Int    // -1 бемоль, 0, 1 диез
) {
    var finger = 0
    var hand = 1
}

/** Шаг урока: все ноты, которые начинаются одновременно (аккорд). */
class Step(val notes: List<Note>) {
    val startMs: Long = notes.minOf { it.startMs }
    val pitches: Set<Int> = notes.map { it.pitch }.toSet()
}

class Score(val title: String, val notes: List<Note>)

private val SHARP_DIA = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)
private val SHARP_ACC = intArrayOf(0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0)

fun noteFromMidi(beat: Double, dur: Double, s: Long, e: Long, pitch: Int, vel: Int): Note {
    val oct = pitch / 12 - 1
    val pc = pitch % 12
    return Note(beat, dur, s, e, pitch, vel, oct * 7 + SHARP_DIA[pc], SHARP_ACC[pc])
}

fun groupSteps(notes: List<Note>): List<Step> {
    val sorted = notes.sortedWith(compareBy({ it.startMs }, { it.pitch }))
    val out = ArrayList<Step>()
    var cur = ArrayList<Note>()
    var t0 = 0L
    for (n in sorted) {
        if (cur.isNotEmpty() && n.startMs - t0 > 35) { out.add(Step(cur)); cur = ArrayList() }
        if (cur.isEmpty()) t0 = n.startMs
        cur.add(n)
    }
    if (cur.isNotEmpty()) out.add(Step(cur))
    return out
}

/** Парсер Standard MIDI File (форматы 0 и 1). */
object SmfParser {
    private class Raw(val s: Long, val e: Long, val p: Int, val v: Int)

    fun parse(d: ByteArray, title: String): Score {
        var pos = 0
        fun u8(): Int = d[pos++].toInt() and 0xFF
        fun u16(): Int { val a = u8(); return (a shl 8) or u8() }
        fun u32(): Int { val a = u16(); return (a shl 16) or u16() }
        fun vlq(): Long {
            var v = 0L
            while (true) {
                val b = u8()
                v = (v shl 7) or (b and 0x7F).toLong()
                if ((b and 0x80) == 0) break
            }
            return v
        }

        require(d.size > 14 && String(d, 0, 4, Charsets.ISO_8859_1) == "MThd") { "Это не MIDI-файл" }
        pos = 4
        val hl = u32(); u16(); u16()
        val div = u16()
        require((div and 0x8000) == 0) { "SMPTE-тайминг не поддерживается" }
        pos = 8 + hl

        val raws = ArrayList<Raw>()
        val tempos = ArrayList<LongArray>()

        while (pos + 8 <= d.size) {
            val tag = String(d, pos, 4, Charsets.ISO_8859_1); pos += 4
            val len = u32()
            val end = minOf(pos + len, d.size)
            if (tag != "MTrk") { pos = end; continue }
            var tick = 0L
            var running = 0
            val open = HashMap<Int, ArrayDeque<LongArray>>()
            try {
                while (pos < end) {
                    tick += vlq()
                    var st = d[pos].toInt() and 0xFF
                    if (st >= 0x80) pos++ else st = running
                    if (st == 0xFF) {
                        val type = u8()
                        val l = vlq().toInt()
                        val ms = pos
                        if (type == 0x51 && l == 3) {
                            val us = ((d[ms].toInt() and 0xFF) shl 16) or
                                    ((d[ms + 1].toInt() and 0xFF) shl 8) or
                                    (d[ms + 2].toInt() and 0xFF)
                            tempos.add(longArrayOf(tick, us.toLong()))
                        }
                        pos = ms + l
                    } else if (st == 0xF0 || st == 0xF7) {
                        pos += vlq().toInt()
                    } else {
                        running = st
                        val ty = st and 0xF0
                        val ch = st and 0x0F
                        if (ty == 0xC0 || ty == 0xD0) {
                            pos++
                        } else {
                            val a = u8()
                            val b = u8()
                            if (ch != 9) { // канал 10 — ударные, пропускаем
                                val key = ch * 128 + a
                                if (ty == 0x90 && b > 0) {
                                    open.getOrPut(key) { ArrayDeque() }.addLast(longArrayOf(tick, b.toLong()))
                                } else if (ty == 0x80 || ty == 0x90) {
                                    val q = open[key]
                                    if (q != null && q.isNotEmpty()) {
                                        val o = q.removeFirst()
                                        raws.add(Raw(o[0], tick, a, o[1].toInt()))
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: IndexOutOfBoundsException) {
                // оборванный трек — берём то, что успели прочитать
            }
            for ((key, q) in open) for (o in q) raws.add(Raw(o[0], tick, key % 128, o[1].toInt()))
            pos = end
        }

        tempos.sortBy { it[0] }
        if (tempos.isEmpty() || tempos[0][0] > 0) tempos.add(0, longArrayOf(0, 500000))
        val msAt = DoubleArray(tempos.size)
        for (i in 1 until tempos.size) {
            msAt[i] = msAt[i - 1] +
                    ((tempos[i][0] - tempos[i - 1][0]) * tempos[i - 1][1]).toDouble() / div / 1000.0
        }
        fun toMs(t: Long): Long {
            var k = 0
            while (k + 1 < tempos.size && tempos[k + 1][0] <= t) k++
            return (msAt[k] + ((t - tempos[k][0]) * tempos[k][1]).toDouble() / div / 1000.0).toLong()
        }

        val notes = raws.filter { it.p in 21..108 }.sortedBy { it.s }.map { r ->
            val e = maxOf(r.e, r.s + 1)
            noteFromMidi(r.s.toDouble() / div, (e - r.s).toDouble() / div, toMs(r.s), toMs(e), r.p, r.v)
        }
        require(notes.isNotEmpty()) { "В MIDI-файле нет нот" }
        return Score(title, notes)
    }
}

/** Парсер MusicXML (.xml / .musicxml / .mxl) — формат, который выдаёт pianoplayer. */
object MusicXml {
    private class X(val beat: Double, val dur: Double, val pitch: Int, val dia: Int,
                    val acc: Int, val finger: Int, val hand: Int)

    private fun unwrap(b: ByteArray): ByteArray {
        if (b.size < 4 || b[0] != 'P'.code.toByte() || b[1] != 'K'.code.toByte()) return b
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(b)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) files[e.name] = z.readBytes()
            }
        }
        val cont = files["META-INF/container.xml"]?.let { String(it) }
        val path = cont?.let { Regex("full-path=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        val byPath = path?.let { files[it] }
        return byPath
            ?: files.entries.firstOrNull { !it.key.startsWith("META-INF") && it.key.endsWith(".xml", true) }?.value
            ?: throw IllegalArgumentException("Пустой .mxl")
    }

    private fun readDur(p: XmlPullParser): Int {
        val depth = p.depth
        var v = 0
        while (true) {
            val e = p.next()
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.END_TAG && p.depth == depth) break
            if (e == XmlPullParser.START_TAG && p.name == "duration") v = p.nextText().trim().toIntOrNull() ?: 0
        }
        return v
    }

    fun parse(bytes: ByteArray, title: String): Score {
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(unwrap(bytes)), null)
        var divisions = 1
        var cursor = 0
        var lastStart = 0
        var part = -1
        var tempo = 0.0
        val out = ArrayList<X>()
        val stepBase = intArrayOf(0, 2, 4, 5, 7, 9, 11)

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "part" -> { part++; cursor = 0; lastStart = 0 }
                    "divisions" -> divisions = maxOf(1, p.nextText().trim().toIntOrNull() ?: 1)
                    "sound" -> if (tempo == 0.0) {
                        val t = p.getAttributeValue(null, "tempo")?.toDoubleOrNull()
                        if (t != null) tempo = t
                    }
                    "backup" -> cursor -= readDur(p)
                    "forward" -> cursor += readDur(p)
                    "note" -> {
                        val depth = p.depth
                        var chord = false; var rest = false; var grace = false; var tieStop = false
                        var step = 0; var alter = 0; var oct = 4; var d = 0; var staff = 1; var fing = 0
                        while (true) {
                            val e = p.next()
                            if (e == XmlPullParser.END_DOCUMENT) break
                            if (e == XmlPullParser.END_TAG && p.depth == depth) break
                            if (e != XmlPullParser.START_TAG) continue
                            when (p.name) {
                                "chord" -> chord = true
                                "rest" -> rest = true
                                "grace" -> grace = true
                                "tie" -> if (p.getAttributeValue(null, "type") == "stop") tieStop = true
                                "step" -> step = maxOf(0, "CDEFGAB".indexOf(p.nextText().trim().firstOrNull() ?: 'C'))
                                "alter" -> alter = p.nextText().trim().toDoubleOrNull()?.toInt() ?: 0
                                "octave" -> oct = p.nextText().trim().toIntOrNull() ?: 4
                                "duration" -> d = p.nextText().trim().toIntOrNull() ?: 0
                                "staff" -> staff = p.nextText().trim().toIntOrNull() ?: 1
                                "fingering" -> {
                                    val c = p.nextText().trim().firstOrNull { it in '1'..'5' }
                                    if (fing == 0 && c != null) fing = c.digitToInt()
                                }
                            }
                        }
                        if (!grace) {
                            val start: Int
                            if (chord) start = lastStart else { start = cursor; lastStart = cursor; cursor += d }
                            if (!rest && !tieStop) {
                                val pitch = (oct + 1) * 12 + stepBase[step] + alter
                                out.add(X(start / divisions.toDouble(), d / divisions.toDouble(), pitch,
                                    oct * 7 + step, alter.coerceIn(-1, 1), fing,
                                    if (part >= 1 || staff >= 2) 2 else 1))
                            }
                        }
                    }
                }
            }
            ev = p.next()
        }

        val bpm = if (tempo > 0) tempo else 120.0
        val notes = out.filter { it.pitch in 21..108 }.sortedBy { it.beat }.map { x ->
            val s = (x.beat * 60000.0 / bpm).toLong()
            val e = ((x.beat + x.dur) * 60000.0 / bpm).toLong()
            Note(x.beat, x.dur, s, e, x.pitch, 80, x.dia, x.acc).also { it.finger = x.finger; it.hand = x.hand }
        }
        require(notes.isNotEmpty()) { "В файле MusicXML нет нот" }
        return Score(title, notes)
    }
}

/** Накладывает аппликатуру из MusicXML (pianoplayer) на ноты MIDI. */
object Fingering {
    fun apply(midi: List<Note>, xml: List<Note>): Int {
        if (midi.isEmpty() || xml.isEmpty()) return 0
        val off = xml.minOf { it.beat } - midi.minOf { it.beat }
        val pool = HashMap<Int, ArrayList<Note>>()
        for (x in xml) pool.getOrPut(x.pitch) { ArrayList() }.add(x)
        var matched = 0
        for (n in midi.sortedBy { it.beat }) {
            val c = pool[n.pitch] ?: continue
            var best = -1
            var bd = 0.3
            for (k in c.indices) {
                val dd = abs(c[k].beat - (n.beat + off))
                if (dd < bd) { bd = dd; best = k }
            }
            if (best >= 0) {
                val x = c.removeAt(best)
                n.finger = x.finger
                n.hand = x.hand
                matched++
            }
        }
        return matched
    }
}
