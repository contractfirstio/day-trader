package daytrader.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

actual object CountdownAudio {
    private val isMacOs: Boolean =
        System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)

    actual suspend fun playTenSecondCountdown(marketLabel: String) = withContext(Dispatchers.IO) {
        for (second in 10 downTo 1) {
            announceSecond(second)
            delay(1000L)
        }
        announceMarketOpen(marketLabel)
    }

    private fun announceSecond(second: Int) {
        if (isMacOs && speak(second.toString())) return
        val frequency = when {
            second >= 7 -> 440.0
            second >= 4 -> 554.0
            else -> 659.0
        }
        playTone(frequency, durationMs = 120)
    }

    private fun announceMarketOpen(marketLabel: String) {
        val phrase = "$marketLabel Market Open"
        if (isMacOs && speak(phrase)) return
        playTone(880.0, durationMs = 280)
    }

    private fun speak(text: String): Boolean = runCatching {
        ProcessBuilder("say", "-r", "210", text)
            .redirectErrorStream(true)
            .start()
    }.isSuccess

    private fun playTone(frequency: Double, durationMs: Int, volume: Double = 0.25) {
        val sampleRate = 44100f
        val format = AudioFormat(sampleRate, 16, 1, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val line = AudioSystem.getLine(info) as SourceDataLine
        line.open(format)
        line.start()
        val sampleCount = (sampleRate * durationMs / 1000f).toInt().coerceAtLeast(1)
        val buffer = ByteArray(sampleCount * 2)
        for (i in 0 until sampleCount) {
            val angle = 2.0 * Math.PI * i * frequency / sampleRate
            val sample = (kotlin.math.sin(angle) * volume * Short.MAX_VALUE).toInt().toShort()
            buffer[i * 2] = (sample.toInt() and 0xFF).toByte()
            buffer[i * 2 + 1] = (sample.toInt() shr 8 and 0xFF).toByte()
        }
        line.write(buffer, 0, buffer.size)
        line.drain()
        line.close()
    }
}
