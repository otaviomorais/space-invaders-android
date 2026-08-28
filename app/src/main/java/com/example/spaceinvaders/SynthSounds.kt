package com.example.spaceinvaders

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import kotlin.math.PI
import kotlin.math.sin

/**
 * Generates 8-bit style sound effects and a looping ambient music bed entirely
 * in code (PCM sample synthesis) and plays them through a [SoundPool].
 *
 * No external audio assets are required, which keeps the APK tiny and lets the
 * build run fully offline. The synth is intentionally simple and deterministic.
 */
class SynthSounds(context: Context) {

    enum class Sfx { SHOT, EXPLODE, BIG_EXPLODE, PLAYER_HIT, SHIELD, POWERUP }

    private val sampleRate = 22050
    private val appContext = context.applicationContext
    private val dir: java.io.File
    private val pool: SoundPool
    private val ids: MutableMap<Sfx, Int> = java.util.concurrent.ConcurrentHashMap()

    @Volatile private var musicStreamId = 0
    @Volatile private var musicStarted = false
    @Volatile private var muted = false

    init {
        dir = appContext.getDir("synth_sounds", Context.MODE_PRIVATE)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            SoundPool.Builder()
                .setMaxStreams(16)
                .setAudioAttributes(attrs)
                .build()
        } else {
            @Suppress("DEPRECATION")
            SoundPool(16, AudioManager.STREAM_MUSIC, 0)
        }
        Thread({
            loadSamples()
            musicFile()
        }, "SynthSounds-Init").apply {
            isDaemon = true
            start()
        }
    }

    // ---------- Sample synthesis ----------

    private fun loadSamples() {
        load(Sfx.SHOT, "synth_shot", synthShot())
        load(Sfx.EXPLODE, "synth_explode", synthExplode(false))
        load(Sfx.BIG_EXPLODE, "synth_big_explode", synthExplode(true))
        load(Sfx.PLAYER_HIT, "synth_player_hit", synthPlayerHit())
        load(Sfx.SHIELD, "synth_shield", synthShield())
        load(Sfx.POWERUP, "synth_powerup", synthPowerup())
    }

    private fun load(sfx: Sfx, name: String, pcm: ShortArray) {
        val f = java.io.File(dir, "$name.wav")
        if (!f.exists() || f.length() == 0L) {
            writeWav(f, pcm)
        }
        ids[sfx] = pool.load(f.absolutePath, 1)
    }

    private fun writeWav(f: java.io.File, pcm: ShortArray) {
        val data = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = pcm[i].toInt()
            data[i * 2] = (v and 0xFF).toByte()
            data[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val header = wavHeader(data.size)
        java.io.FileOutputStream(f).use { out ->
            out.write(header)
            out.write(data)
        }
    }

    private fun wavHeader(dataLen: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val le = java.io.DataOutputStream(out)
        fun ascii(s: String) = le.writeBytes(s)
        fun le32(v: Int) { le.writeByte(v and 0xFF); le.writeByte((v shr 8) and 0xFF); le.writeByte((v shr 16) and 0xFF); le.writeByte((v shr 24) and 0xFF) }
        fun le16(v: Int) { le.writeByte(v and 0xFF); le.writeByte((v shr 8) and 0xFF) }

        ascii("RIFF"); le32(36 + dataLen); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(1); le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
        ascii("data"); le32(dataLen)
        return out.toByteArray()
    }

    // ---------- Sound recipes ----------

    /** Laser blip: quick descending sweep. */
    private fun synthShot(): ShortArray {
        val n = (sampleRate * 0.09).toInt()
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val freq = 1500.0 - 900.0 * (i.toDouble() / n)
            phase += 2 * PI * freq / sampleRate
            val env = (1.0 - i.toDouble() / n)
            val v = (sin(phase) * 0.55 + sin(phase * 2.0) * 0.2).coerceIn(-1.0, 1.0) * env
            out[i] = (v * 32767.0).toInt().toShort()
        }
        return out
    }

    /** Noise burst explosion; bigger = longer, deeper rumble. */
    private fun synthExplode(big: Boolean): ShortArray {
        val dur = if (big) 0.55 else 0.35
        val n = (sampleRate * dur).toInt()
        val out = ShortArray(n)
        var prev = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val env = (1.0 - t / dur).let { it * it }
            val noise = (kotlin.random.Random.nextDouble() * 2.0 - 1.0)
            // rough lowpass
            prev = prev * 0.85 + noise * 0.15
            val tone = sin(2 * PI * (if (big) 55.0 else 90.0) * t) * 0.4
            val v = (prev * 1.1 + tone).coerceIn(-1.0, 1.0) * env
            out[i] = (v * 32767.0).toInt().toShort()
        }
        return out
    }

    /** Harsh descending hit. */
    private fun synthPlayerHit(): ShortArray {
        val dur = 0.28
        val n = (sampleRate * dur).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val freq = 400.0 - 260.0 * (i.toDouble() / n)
            val env = (1.0 - t / dur)
            val v = (sin(2 * PI * freq * t) * 0.6 + sin(2 * PI * freq * 0.5 * t) * 0.3) * env
            out[i] = (v * 32767.0).toInt().toShort()
        }
        return out
    }

    /** Metallic ring for shield/armor absorb. */
    private fun synthShield(): ShortArray {
        val n = (sampleRate * 0.2).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val env = (1.0 - i.toDouble() / n)
            val v = (sin(2 * PI * 660.0 * t) * 0.5 + sin(2 * PI * 990.0 * t) * 0.25) * env
            out[i] = (v * 32767.0).toInt().toShort()
        }
        return out
    }

    /** Happy ascending arpeggio. */
    private fun synthPowerup(): ShortArray {
        val dur = 0.28
        val n = (sampleRate * dur).toInt()
        val out = ShortArray(n)
        val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.5)
        val noteLen = n / notes.size
        for (i in 0 until n) {
            val ni = (i / noteLen).coerceAtMost(notes.size - 1)
            val freq = notes[ni]
            val lt = (i % noteLen).toDouble() / noteLen
            val env = (1.0 - lt) * 0.6
            val v = (sin(2 * PI * freq * i / sampleRate) * env).coerceIn(-1.0, 1.0)
            out[i] = (v * 32767.0).toInt().toShort()
        }
        return out
    }

    // ---------- Playback ----------

    fun play(sfx: Sfx) {
        if (muted) return
        val id = ids[sfx] ?: return
        try {
            pool.play(id, 0.95f, 0.95f, 1, 0, 1f)
        } catch (_: Exception) {}
    }

    /** Starts (or restarts) the ambient loop. */
    fun startMusic() {
        if (muted) return
        if (musicStarted) return
        musicStarted = true
        Thread({
            val file = musicFile()
            if (!musicStarted) return@Thread
            val sampleId = pool.load(file.absolutePath, 1)
            pool.setOnLoadCompleteListener { _, sid, _ ->
                if (sid == sampleId && musicStarted) {
                    try {
                        // pool.play returns a stream id; keep it to stop later.
                        musicStreamId = pool.play(sampleId, 0.25f, 0.25f, 1, -1, 1f)
                    } catch (_: Exception) {}
                }
            }
        }, "SynthSounds-Music").apply {
            isDaemon = true
            start()
        }
    }

    fun stopMusic() {
        musicStarted = false
        if (musicStreamId != 0) {
            try {
                pool.stop(musicStreamId)
            } catch (_: Exception) {}
            musicStreamId = 0
        }
    }

    private fun musicFile(): java.io.File {
        val f = java.io.File(dir, "synth_music.wav")
        if (!f.exists() || f.length() == 0L) writeWav(f, synthMusic())
        return f
    }

    /** Simple looping minor-key bass arpeggio (8 seconds). */
    private fun synthMusic(): ShortArray {
        val beatsPerBar = 8
        val bpm = 100
        val beatDur = 60.0 / bpm
        val barDur = beatsPerBar * beatDur
        val n = (sampleRate * barDur).toInt()
        val out = ShortArray(n)
        // minor scale bass notes per beat
        val bassFreqs = doubleArrayOf(110.0, 110.0, 130.81, 110.0, 87.31, 87.31, 98.0, 123.47)
        val padFreqs = doubleArrayOf(220.0, 261.63, 329.63)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val beat = (t / beatDur).toInt() % beatsPerBar
            val bt = (t % beatDur) / beatDur
            val bassF = bassFreqs[beat]
            // pluck envelope
            val bassEnv = kotlin.math.exp(-bt * 8.0)
            val bass = sin(2 * PI * bassF * t) * bassEnv * 0.32
            // soft pad
            var pad = 0.0
            for (p in padFreqs) {
                pad += sin(2 * PI * p * t) * 0.035
            }
            val v = (bass + pad).coerceIn(-1.0, 1.0)
            out[i] = (v * 32767.0).toInt().toShort()
        }
        return out
    }

    fun setMuted(m: Boolean) {
        muted = m
        if (m) stopMusic()
    }

    fun release() {
        try {
            stopMusic()
            pool.release()
        } catch (_: Exception) {}
    }
}