package com.example.pianoteacher

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

enum class Mode { NONE, LISTEN, WATCH, REMEMBER }

/** Проигрывает шаги урока на пианино (через MIDI OUT) и/или на встроенном синтезаторе. */
class Player(
    steps: List<Step>, speed: Float,
    private val send: (Int, Int, Int) -> Unit,
    private val ui: Handler,
    private val onState: (Int, Map<Int, Note>) -> Unit,
    private val onDone: () -> Unit
) {
    private class Ev(val t: Long, val on: Boolean, val n: Note, val step: Int)

    private val events: List<Ev>
    @Volatile private var stopped = false
    private var thread: Thread? = null

    init {
        val l = ArrayList<Ev>()
        val base = if (steps.isEmpty()) 0L else steps[0].startMs
        for ((i, s) in steps.withIndex()) for (n in s.notes) {
            val a = ((n.startMs - base) / speed).toLong() + 600
            val b = ((maxOf(n.endMs, n.startMs + 60) - base) / speed).toLong() + 600
            l.add(Ev(a, true, n, i)); l.add(Ev(b, false, n, i))
        }
        events = l.sortedWith(compareBy({ it.t }, { if (it.on) 1 else 0 }))
    }

    fun start() { thread = Thread { loop() }.also { it.start() } }

    fun stop() { stopped = true; thread?.interrupt() }

    private fun loop() {
        val active = HashMap<Int, Note>()
        val t0 = SystemClock.uptimeMillis()
        try {
            for (e in events) {
                val w = t0 + e.t - SystemClock.uptimeMillis()
                if (w > 0) Thread.sleep(w)
                if (stopped) break
                if (e.on) { send(0x90, e.n.pitch, maxOf(e.n.vel, 40)); active[e.n.pitch] = e.n }
                else { send(0x80, e.n.pitch, 0); if (active[e.n.pitch] === e.n) active.remove(e.n.pitch) }
                val snap = HashMap<Int, Note>(active)
                val st = e.step
                ui.post { if (!stopped) onState(st, snap) }
            }
        } catch (ex: InterruptedException) {
        }
        for (p in active.keys) send(0x80, p, 0)
        if (!stopped) ui.post { onDone() }
    }
}

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var midi: MidiManager? = null
    private val synth = Synth()
    @Volatile private var tabletSound = false

    private var device: MidiDevice? = null
    private var outPort: MidiOutputPort? = null
    @Volatile private var inPort: MidiInputPort? = null
    private var curId = -1
    private var infos: List<MidiDeviceInfo> = emptyList()
    private var connInfo = "MIDI: подключите пианино по USB (OTG)"
    private var rs = 0

    private var midiScore: Score? = null
    private var xmlScore: Score? = null
    private var notes: List<Note> = emptyList()
    private var steps: List<Step> = emptyList()
    private var songTitle = ""
    private var matchInfo = ""

    private var mode = Mode.NONE
    private var idx = 0
    private var mistakes = 0
    private val held = HashSet<Int>()
    private val wrong = HashSet<Int>()
    private val done = HashSet<Int>()
    private var playing: Map<Int, Note> = emptyMap()
    private var player: Player? = null

    private lateinit var staff: StaffView
    private lateinit var kb: KeyboardView
    private lateinit var info: TextView
    private lateinit var spinDev: Spinner
    private lateinit var spinHands: Spinner
    private lateinit var seek: SeekBar
    private lateinit var tempoLabel: TextView
    private lateinit var cbShowNotes: CheckBox
    private lateinit var btnListen: Button
    private lateinit var btnWatch: Button
    private lateinit var btnRemember: Button

    // ---------- UI ----------

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; setAllCaps(false); setOnClickListener { f() }
        }
        fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }

        val r1 = row()
        r1.addView(btn("Открыть MIDI") { pick(1) })
        r1.addView(btn("Аппликатура (pianoplayer)") { pick(2) })
        r1.addView(TextView(this).apply { text = "  MIDI: " })
        spinDev = Spinner(this)
        r1.addView(spinDev, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(r1)

        val r2 = row()
        btnListen = btn("1 · Слушай (LISTEN, 21)") { startMode(Mode.LISTEN) }
        btnWatch = btn("2 · Смотри (WATCH, 22)") { startMode(Mode.WATCH) }
        btnRemember = btn("3 · Вспоминай (REMEMBER, 23)") { startMode(Mode.REMEMBER) }
        r2.addView(btnListen); r2.addView(btnWatch); r2.addView(btnRemember)
        r2.addView(btn("■ Стоп") { stopAll() })
        root.addView(r2)

        val r3 = row()
        r3.addView(TextView(this).apply { text = "Руки: " })
        spinHands = Spinner(this)
        spinHands.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Обе", "Правая", "Левая"))
        spinHands.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { rebuild() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        r3.addView(spinHands)
        tempoLabel = TextView(this).apply { text = "   Темп 100% " }
        r3.addView(tempoLabel)
        seek = SeekBar(this).apply {
            max = 100; progress = 60
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                    tempoLabel.text = "   Темп ${(speed() * 100).toInt()}% "
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        r3.addView(seek, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r3.addView(CheckBox(this).apply {
            text = "Звук на планшете"
            setOnCheckedChangeListener { _, c -> tabletSound = c }
        })
        cbShowNotes = CheckBox(this).apply {
            text = "Ноты в «Вспоминай»"
            setOnCheckedChangeListener { _, _ -> refresh() }
        }
        r3.addView(cbShowNotes)
        root.addView(r3)

        info = TextView(this).apply { textSize = 14f; setPadding(dp(4), dp(2), dp(4), dp(2)) }
        root.addView(info)

        staff = StaffView(this)
        root.addView(staff, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        kb = KeyboardView(this)
        root.addView(kb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150)))
        setContentView(root)

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) {
            connInfo = "Это устройство не поддерживает android.media.midi"
        } else {
            val m = getSystemService(MIDI_SERVICE) as MidiManager
            midi = m
            spinDev.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    infos.getOrNull(pos)?.takeIf { it.id != curId }?.let { openDevice(it) }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            m.registerDeviceCallback(devCb, ui)
            refreshDevices()
        }
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayer()
        closeDevice()
        midi?.unregisterDeviceCallback(devCb)
        synth.stop()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun speed() = 0.25f + seek.progress / 100f * 1.25f

    private fun refresh() {
        val st = steps.getOrNull(idx)
        staff.steps = steps
        staff.index = idx
        staff.showFingers = mode != Mode.REMEMBER
        staff.hideNotes = mode == Mode.REMEMBER && !cbShowNotes.isChecked
        kb.held = held
        kb.wrong = wrong
        kb.targets = when (mode) {
            Mode.WATCH -> st?.notes?.associateBy { it.pitch } ?: emptyMap()
            Mode.LISTEN -> playing
            else -> emptyMap()
        }
        kb.done = if (mode == Mode.WATCH) done else emptySet()
        kb.showFingers = mode != Mode.REMEMBER
        staff.invalidate(); kb.invalidate()

        for ((b, m) in listOf(btnListen to Mode.LISTEN, btnWatch to Mode.WATCH, btnRemember to Mode.REMEMBER)) {
            b.setBackgroundColor(if (mode == m) 0xFF9ADFA8.toInt() else 0xFFDDDDDD.toInt())
        }
        val sb = StringBuilder(connInfo)
        if (songTitle.isNotEmpty()) {
            sb.append("   |   ").append(songTitle).append(": ").append(notes.size).append(" нот")
            if (matchInfo.isNotEmpty()) sb.append(", ").append(matchInfo)
            sb.append("   |   шаг ").append(minOf(idx + 1, steps.size)).append("/").append(steps.size)
            if (mode == Mode.WATCH || mode == Mode.REMEMBER) sb.append(", ошибок: ").append(mistakes)
        }
        info.text = sb.toString()
    }

    // ---------- Режимы урока ----------

    private fun startMode(m: Mode) {
        val was = mode
        stopPlayer()
        held.clear(); wrong.clear(); done.clear()
        if (was == m) { mode = Mode.NONE; idx = 0; refresh(); return }
        if (steps.isEmpty()) { toast("Сначала загрузите MIDI-файл"); return }
        mode = m; idx = 0; mistakes = 0
        if (m == Mode.LISTEN) {
            player = Player(steps, speed(), { s, a, v -> sendMidi(s, a, v) }, ui,
                { i, act -> idx = i; playing = act; refresh() },
                { mode = Mode.NONE; idx = 0; playing = emptyMap(); player = null; refresh() })
            player?.start()
        }
        refresh()
    }

    private fun stopAll() { stopPlayer(); mode = Mode.NONE; idx = 0; done.clear(); wrong.clear(); refresh() }

    private fun stopPlayer() { player?.stop(); player = null; playing = emptyMap() }

    private fun rebuild() {
        stopPlayer(); mode = Mode.NONE
        val use = when (spinHands.selectedItemPosition) {
            1 -> notes.filter { it.hand == 1 }
            2 -> notes.filter { it.hand == 2 }
            else -> notes
        }
        steps = groupSteps(use)
        idx = 0; mistakes = 0; done.clear(); wrong.clear()
        refresh()
    }

    private fun advance() {
        idx++; done.clear()
        if (idx >= steps.size) {
            toast("Готово! Ошибок: $mistakes")
            mode = Mode.NONE; idx = 0
        }
    }

    /** Нота с пианино (вызывается в UI-потоке). */
    private fun onKey(p: Int, on: Boolean) {
        val training = mode == Mode.WATCH || mode == Mode.REMEMBER
        val st = if (training) steps.getOrNull(idx) else null
        if (on) {
            // Три нижние клавиши (21/22/23) выбирают урок, если эта нота не нужна в текущем шаге
            if (p in 21..23 && (st == null || p !in st.pitches)) {
                when (p) { 21 -> startMode(Mode.LISTEN); 22 -> startMode(Mode.WATCH); else -> startMode(Mode.REMEMBER) }
                return
            }
            held.add(p)
            if (tabletSound) synth.on(p, 90)
            if (st != null) {
                if (p in st.pitches) {
                    done.add(p)
                    if (done.containsAll(st.pitches)) advance()
                } else { wrong.add(p); mistakes++ }
            }
        } else {
            held.remove(p); wrong.remove(p)
            if (tabletSound) synth.off(p)
        }
        refresh()
    }

    // ---------- MIDI ----------

    private val devCb = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) { refreshDevices() }
        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (info.id == curId) { closeDevice(); connInfo = "MIDI: устройство отключено"; refresh() }
            refreshDevices()
        }
    }

    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            parseMidi(msg, offset, count)
        }
    }

    private fun parseMidi(m: ByteArray, off: Int, cnt: Int) {
        var i = off
        val end = off + cnt
        while (i < end) {
            val b = m[i].toInt() and 0xFF
            if (b >= 0xF8) { i++; continue }
            if (b >= 0x80) {
                i++
                if (b >= 0xF0) {
                    rs = 0
                    if (b == 0xF0) { while (i < end && (m[i].toInt() and 0xFF) != 0xF7) i++; i++ }
                } else rs = b
                continue
            }
            if (rs == 0) { i++; continue }
            val type = rs and 0xF0
            val need = if (type == 0xC0 || type == 0xD0) 1 else 2
            if (i + need > end) break
            val d1 = m[i].toInt() and 0x7F
            val d2 = if (need == 2) m[i + 1].toInt() and 0x7F else 0
            i += need
            if (type == 0x90) { val on = d2 > 0; ui.post { onKey(d1, on) } }
            else if (type == 0x80) ui.post { onKey(d1, false) }
        }
    }

    private fun refreshDevices() {
        val m = midi ?: return
        infos = m.devices.toList()
        val names = infos.map { it.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI #${it.id}" }
        spinDev.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            if (names.isEmpty()) listOf("нет MIDI-устройств") else names)
        val sel = infos.indexOfFirst { it.id == curId }
        if (sel >= 0) spinDev.setSelection(sel)
        if (curId == -1 && infos.isNotEmpty()) openDevice(infos[0])
    }

    private fun openDevice(info: MidiDeviceInfo) {
        val m = midi ?: return
        closeDevice()
        curId = info.id
        m.openDevice(info, { dev ->
            if (dev == null) {
                connInfo = "MIDI: не удалось открыть устройство"; curId = -1
            } else {
                device = dev
                rs = 0
                if (info.outputPortCount > 0) {
                    outPort = dev.openOutputPort(0)
                    outPort?.connect(receiver)
                }
                if (info.inputPortCount > 0) inPort = dev.openInputPort(0)
                val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI"
                connInfo = "MIDI: $name подключено" +
                        (if (outPort == null) " (нет выхода — клавиши не слышно)" else "") +
                        (if (inPort == null) " (нет входа — звук только на планшете)" else "")
            }
            refresh()
        }, ui)
    }

    private fun closeDevice() {
        try { outPort?.disconnect(receiver) } catch (e: Exception) {}
        try { outPort?.close() } catch (e: Exception) {}
        try { inPort?.close() } catch (e: Exception) {}
        try { device?.close() } catch (e: Exception) {}
        outPort = null; inPort = null; device = null; curId = -1
    }

    /** Отправка ноты на пианино (и/или во встроенный синтезатор). Вызывается из потока плеера. */
    private fun sendMidi(status: Int, a: Int, v: Int) {
        try { inPort?.send(byteArrayOf(status.toByte(), a.toByte(), v.toByte()), 0, 3) } catch (e: Exception) {}
        if (inPort == null || tabletSound) {
            if (status == 0x90 && v > 0) synth.on(a, v) else synth.off(a)
        }
    }

    // ---------- Файлы ----------

    private fun pick(code: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }
        startActivityForResult(i, code)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            handleFile(req, bytes, nameOf(uri))
        } catch (e: Exception) {
            toast("Ошибка: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun nameOf(uri: Uri): String {
        var n: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) n = c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            }
        } catch (e: Exception) {}
        return n ?: "файл"
    }

    private fun handleFile(req: Int, bytes: ByteArray, name: String) {
        val isMidi = bytes.size > 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "MThd"
        if (isMidi) {
            if (req == 2) throw IllegalArgumentException("Аппликатура — это MusicXML-файл от pianoplayer, а не MIDI")
            midiScore = SmfParser.parse(bytes, name)
            xmlScore = null
        } else {
            val sc = MusicXml.parse(bytes, name)
            if (req == 1) midiScore = null
            xmlScore = sc
        }
        combine()
    }

    private fun combine() {
        val base = midiScore ?: xmlScore ?: return
        notes = base.notes
        songTitle = base.title
        matchInfo = ""
        val m = midiScore
        val x = xmlScore
        if (m != null) {
            for (n in notes) { n.finger = 0; n.hand = if (n.pitch >= 60) 1 else 2 }
            if (x != null) {
                val k = Fingering.apply(notes, x.notes)
                matchInfo = "аппликатура: $k из ${notes.size} нот (${x.title})"
            }
        } else {
            matchInfo = if (notes.any { it.finger > 0 }) "с аппликатурой" else "без аппликатуры"
        }
        rebuild()
    }
}
