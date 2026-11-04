package com.example.pianoteacher

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
    steps: List<Step>, from: Int, speed: Float,
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
        val base = if (from in steps.indices) steps[from].startMs else 0L
        for (i in from until steps.size) for (n in steps[i].notes) {
            val a = ((n.startMs - base) / speed).toLong() + 400
            val b = ((maxOf(n.endMs, n.startMs + 60) - base) / speed).toLong() + 400
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

/** Сколько лишних (не входящих в шаг) клавиш может быть нажато одновременно с нужными. */
const val EXTRA_ALLOWED = 1

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
    private val held = HashSet<Int>()      // клавиши, которые зажаты сейчас
    private val armed = HashSet<Int>()     // нажаты уже после начала текущего шага и всё ещё зажаты
    private val wrong = HashSet<Int>()     // зажатые клавиши, которые были нажаты ошибочно
    private var playing: Map<Int, Note> = emptyMap()
    private var player: Player? = null
    private var seekGuard = false
    private var resumeAfterSeek = false

    private lateinit var staff: StaffView
    private lateinit var kb: KeyboardView
    private lateinit var hands: HandsView
    private lateinit var info: TextView
    private lateinit var spinDev: Spinner
    private lateinit var spinHands: Spinner
    private lateinit var seek: SeekBar
    private lateinit var posSeek: SeekBar
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
            text = t; setAllCaps(false); textSize = 13f
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setOnClickListener { f() }
        }
        fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        fun small(t: String) = TextView(this).apply { text = t; textSize = 13f }
        fun cb(t: String, f: (Boolean) -> Unit) = CheckBox(this).apply {
            text = t; textSize = 13f; setOnCheckedChangeListener { _, c -> f(c) }
        }
        val grow = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(2), dp(8), dp(2)) }

        val r1 = row()
        r1.addView(btn("Открыть MIDI") { pick(1) })
        r1.addView(btn("Аппликатура (pianoplayer)") { pick(2) })
        r1.addView(small("  MIDI: "))
        spinDev = Spinner(this)
        r1.addView(spinDev, grow())
        r1.addView(cb("Картинка рук") { hands.visibility = if (it) View.VISIBLE else View.GONE })
        r1.addView(cb("Звук на планшете") { tabletSound = it })
        root.addView(r1)

        val r2 = row()
        btnListen = btn("1 · Слушай") { startMode(Mode.LISTEN) }
        btnWatch = btn("2 · Смотри") { startMode(Mode.WATCH) }
        btnRemember = btn("3 · Вспоминай") { startMode(Mode.REMEMBER) }
        r2.addView(btnListen); r2.addView(btnWatch); r2.addView(btnRemember)
        r2.addView(btn("■ Стоп") { stopAll() })
        r2.addView(btn("⏮ В начало") { setPosition(0) })
        r2.addView(small("  Партия: "))
        spinHands = Spinner(this)
        spinHands.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Обе руки", "Правая", "Левая"))
        spinHands.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { rebuild() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        r2.addView(spinHands)
        tempoLabel = small("  Темп 100% ")
        r2.addView(tempoLabel)
        seek = SeekBar(this).apply {
            max = 100; progress = 60
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                    tempoLabel.text = "  Темп ${(speed() * 100).toInt()}% "
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        r2.addView(seek, grow())
        root.addView(r2)

        val r3 = row()
        r3.addView(small("Позиция: "))
        posSeek = SeekBar(this).apply {
            max = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                    if (seekGuard || !u) return
                    idx = v; armed.clear(); refresh()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {
                    if (mode == Mode.LISTEN) { stopPlayer(); resumeAfterSeek = true }
                }
                override fun onStopTrackingTouch(s: SeekBar?) {
                    if (resumeAfterSeek) { resumeAfterSeek = false; startPlayer(idx) }
                }
            })
        }
        r3.addView(posSeek, grow())
        cbShowNotes = cb("Ноты в «Вспоминай»") { refresh() }
        r3.addView(cbShowNotes)
        root.addView(r3)

        info = TextView(this).apply { textSize = 12f; setPadding(dp(4), 0, dp(4), dp(2)) }
        root.addView(info)

        val mid = row()
        staff = StaffView(this)
        staff.onSeek = { setPosition(it) }
        mid.addView(staff, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        hands = HandsView(this)
        hands.visibility = View.GONE
        mid.addView(hands, LinearLayout.LayoutParams(dp(240), LinearLayout.LayoutParams.MATCH_PARENT))
        root.addView(mid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        kb = KeyboardView(this)
        root.addView(kb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(120)))
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
        val targets: Map<Int, Note> = when (mode) {
            Mode.WATCH -> st?.notes?.associateBy { it.pitch } ?: emptyMap()
            Mode.LISTEN -> playing
            else -> emptyMap()
        }
        val fingers = mode != Mode.REMEMBER

        staff.steps = steps
        staff.index = idx
        staff.showFingers = fingers
        staff.hideNotes = mode == Mode.REMEMBER && !cbShowNotes.isChecked
        staff.held = held
        staff.wrong = wrong
        kb.held = held; kb.wrong = wrong; kb.targets = targets; kb.showFingers = fingers
        hands.targets = targets; hands.showFingers = fingers
        staff.invalidate(); kb.invalidate(); hands.invalidate()

        val mx = maxOf(0, steps.size - 1)
        seekGuard = true
        if (posSeek.max != mx) posSeek.max = mx
        posSeek.progress = idx
        seekGuard = false

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
        armed.clear(); wrong.clear()
        if (was == m) { mode = Mode.NONE; refresh(); return }
        if (steps.isEmpty()) { toast("Сначала загрузите MIDI-файл"); return }
        mode = m
        mistakes = 0
        if (idx >= steps.size) idx = 0
        if (m == Mode.LISTEN) startPlayer(idx)
        refresh()
    }

    private fun startPlayer(from: Int) {
        player?.stop()
        player = Player(steps, from, speed(), { s, a, v -> sendMidi(s, a, v) }, ui,
            { i, act -> idx = i; playing = act; refresh() },
            { mode = Mode.NONE; idx = 0; playing = emptyMap(); player = null; refresh() })
        player?.start()
    }

    private fun stopAll() { stopPlayer(); mode = Mode.NONE; armed.clear(); wrong.clear(); refresh() }

    private fun stopPlayer() { player?.stop(); player = null; playing = emptyMap() }

    /** Выбор позиции (тап по нотам, ползунок, «В начало»). */
    private fun setPosition(i: Int) {
        if (steps.isEmpty()) return
        idx = i.coerceIn(0, steps.size - 1)
        armed.clear()
        if (mode == Mode.LISTEN) { playing = emptyMap(); startPlayer(idx) }
        refresh()
    }

    private fun rebuild() {
        stopPlayer(); mode = Mode.NONE
        val use = when (spinHands.selectedItemPosition) {
            1 -> notes.filter { it.hand == 1 }
            2 -> notes.filter { it.hand == 2 }
            else -> notes
        }
        steps = groupSteps(use)
        idx = 0; mistakes = 0; armed.clear(); wrong.clear()
        refresh()
    }

    private fun advance() {
        idx++
        armed.clear()   // зажатые клавиши надо отпустить и нажать заново, чтобы они считались для следующего шага
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
            held.add(p)
            if (tabletSound) synth.on(p, 90)
            if (st != null) {
                armed.add(p)
                if (p !in st.pitches) { wrong.add(p); mistakes++ }
                // шаг засчитывается, когда ВСЕ нужные клавиши зажаты одновременно,
                // а лишних зажато не больше EXTRA_ALLOWED
                if (armed.containsAll(st.pitches) && held.count { it !in st.pitches } <= EXTRA_ALLOWED) advance()
            }
        } else {
            held.remove(p); wrong.remove(p); armed.remove(p)
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
