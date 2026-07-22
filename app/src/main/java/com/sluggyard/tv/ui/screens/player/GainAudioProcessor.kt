@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sluggyard.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Applies a positive audio gain (in dB) to 16-bit PCM or float PCM audio
 * passing through the ExoPlayer audio pipeline.
 *
 * Used to amplify quiet streams beyond system max volume. Values below the
 * minimum dB are treated as "no gain" so the processor can stay in the chain
 * without doing work.
 */
internal class GainAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var gainDb: Int = AUDIO_AMPLIFICATION_MIN_DB

    @Volatile
    private var linearScale: Float = 1f

    fun setGainDb(db: Int) {
        val clamped = db.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
        gainDb = clamped
        linearScale = dbToLinearScale(clamped)
    }

    fun isGainEnabled(): Boolean = gainDb != AUDIO_AMPLIFICATION_MIN_DB

    override fun isActive(): Boolean = super.isActive() && isGainEnabled()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val inputSize = inputBuffer.remaining()
        val outputBuffer = replaceOutputBuffer(inputSize)
        val scale = linearScale

        if (scale == 1f) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> amplifyPcm16(inputBuffer, outputBuffer, scale)
            C.ENCODING_PCM_FLOAT -> amplifyPcmFloat(inputBuffer, outputBuffer, scale)
            else -> outputBuffer.put(inputBuffer)
        }
        outputBuffer.flip()
    }

    private fun amplifyPcm16(input: ByteBuffer, output: ByteBuffer, scale: Float) {
        input.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())
        while (input.remaining() >= 2) {
            val sample = input.short.toInt()
            val amplified = (sample * scale)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output.putShort(amplified.toShort())
        }
        if (input.hasRemaining()) output.put(input)
    }

    private fun amplifyPcmFloat(input: ByteBuffer, output: ByteBuffer, scale: Float) {
        input.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())
        while (input.remaining() >= 4) {
            val sample = input.float
            output.putFloat((sample * scale).coerceIn(-1f, 1f))
        }
        if (input.hasRemaining()) output.put(input)
    }

    private fun dbToLinearScale(db: Int): Float {
        if (db == 0) return 1f
        return 10.0.pow(db / 20.0).toFloat()
    }
}