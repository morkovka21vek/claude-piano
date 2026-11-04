package com.example.pianoteacher

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Простейший полифонический синтезатор (запасной звук на планшете). */
class Synth {
    private class Voice(val pitch: Int, val inc: Double, var amp: Double) {
        var ph = 0.0
        var down = false
    }

    private val sr = 22050
    private val voices = ArrayList<Voice>()
    @Volatile private var running = false

    private fun start() {
        if (running) return
        running = true
        Thread {
            val buf = ShortArray(512)
            val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            @Suppress("DEPRECATION")
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC, sr, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096), AudioTrack.MODE_STREAM
            )
            track.play()
            while (running) {
                synchronized(voices) {
                    for (i in buf.indices) {
                        var s = 0.0
                        val vi = voices.iterator()
                        while (vi.hasNext()) {
                            val v = vi.next()
                            s += (sin(v.ph) + 0.4 * sin(2 * v.ph) + 0.15 * sin(3 * v.ph)) * v.amp
                            v.ph += v.inc
                            if (v.ph > 2 * PI) v.ph -= 2 * PI
                            v.amp *= if (v.down) 0.9990 else 0.99995
                            if (v.amp < 0.0005) vi.remove()
                        }
                        buf[i] = (s.coerceIn(-1.0, 1.0) * 12000).toInt().toShort()
                    }
                }
                track.write(buf, 0, buf.size)
            }
            track.stop()
            track.release()
        }.start()
    }

    fun on(pitch: Int, vel: Int) {
        start()
        val f = 440.0 * 2.0.pow((pitch - 69) / 12.0)
        synchronized(voices) {
            if (voices.size > 24) voices.removeAt(0)
            voices.add(Voice(pitch, 2 * PI * f / sr, vel / 127.0 * 0.25))
        }
    }

    fun off(pitch: Int) {
        synchronized(voices) { for (v in voices) if (v.pitch == pitch) v.down = true }
    }

    fun stop() { running = false }
}
