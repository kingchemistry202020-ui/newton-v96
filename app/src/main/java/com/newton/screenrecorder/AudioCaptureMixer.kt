package com.newton.screenrecorder

import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.max

class AudioCaptureMixer(
    private val mediaProjection: MediaProjection,
    private val outputFile: File,
    private val noiseReductionEnabled: Boolean
) {
    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val CHANNEL_COUNT = 1
        private const val PCM_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_SAMPLES = 2048
    }

    private val internalQueue = ArrayBlockingQueue<ShortArray>(8)
    private val micQueue = ArrayBlockingQueue<ShortArray>(8)

    @Volatile private var running = false
    private var internalRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private var internalThread: Thread? = null
    private var micThread: Thread? = null
    private var mixerThread: Thread? = null

    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var gainControl: AutomaticGainControl? = null

    fun start() {
        if (running) return

        val format = AudioFormat.Builder()
            .setEncoding(PCM_ENCODING)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuffer = max(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, PCM_ENCODING),
            FRAME_SAMPLES * 2 * 4
        )

        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        internalRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuffer)
            .build()

        micRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuffer)
            .build()

        if (noiseReductionEnabled) attachVoiceEffects(micRecord!!)

        running = true
        internalRecord!!.startRecording()
        micRecord!!.startRecording()

        internalThread = captureThread(internalRecord!!, internalQueue, "NewtonInternalAudio")
        micThread = captureThread(micRecord!!, micQueue, "NewtonMicAudio")

        mixerThread = thread(start = true, name = "NewtonAudioMixer") {
            val encoder = AacFileEncoder(outputFile, SAMPLE_RATE, CHANNEL_COUNT)
            try {
                encoder.start()
                while (running || internalQueue.isNotEmpty() || micQueue.isNotEmpty()) {
                    val internal = internalQueue.poll(60, TimeUnit.MILLISECONDS)
                    val mic = micQueue.poll(60, TimeUnit.MILLISECONDS)
                    if (internal == null && mic == null) continue

                    val length = max(internal?.size ?: 0, mic?.size ?: 0)
                    if (length <= 0) continue
                    val mixed = ShortArray(length)

                    for (i in 0 until length) {
                        val phone = internal?.getOrNull(i)?.toInt() ?: 0
                        val voice = mic?.getOrNull(i)?.toInt() ?: 0
                        // Voice slightly dominant; internal audio reduced to avoid clipping.
                        val sample = (voice * 0.95f + phone * 0.70f).toInt()
                        mixed[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                    encoder.writePcm(mixed)
                }
            } finally {
                encoder.stop()
            }
        }
    }

    private fun captureThread(
        record: AudioRecord,
        queue: ArrayBlockingQueue<ShortArray>,
        threadName: String
    ): Thread = thread(start = true, name = threadName) {
        val buffer = ShortArray(FRAME_SAMPLES)
        while (running) {
            val read = try {
                record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            } catch (_: Exception) {
                break
            }
            if (read > 0) {
                val chunk = buffer.copyOf(read)
                if (!queue.offer(chunk)) {
                    queue.poll()
                    queue.offer(chunk)
                }
            }
        }
    }

    private fun attachVoiceEffects(record: AudioRecord) {
        val session = record.audioSessionId
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(session)?.apply { enabled = true }
        }
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(session)?.apply { enabled = true }
        }
        if (AutomaticGainControl.isAvailable()) {
            gainControl = AutomaticGainControl.create(session)?.apply { enabled = true }
        }
    }

    fun stop() {
        if (!running) return
        running = false

        try { internalRecord?.stop() } catch (_: Exception) {}
        try { micRecord?.stop() } catch (_: Exception) {}

        try { internalThread?.join(1000) } catch (_: Exception) {}
        try { micThread?.join(1000) } catch (_: Exception) {}
        try { mixerThread?.join(4000) } catch (_: Exception) {}

        noiseSuppressor?.release()
        echoCanceler?.release()
        gainControl?.release()

        internalRecord?.release()
        micRecord?.release()
        internalRecord = null
        micRecord = null
    }
}

private class AacFileEncoder(
    private val outputFile: File,
    private val sampleRate: Int,
    private val channelCount: Int
) {
    private lateinit var codec: MediaCodec
    private lateinit var muxer: MediaMuxer
    private var muxerStarted = false
    private var trackIndex = -1
    private var totalSamples = 0L
    private val info = MediaCodec.BufferInfo()

    fun start() {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun writePcm(samples: ShortArray) {
        var offsetSamples = 0
        while (offsetSamples < samples.size) {
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val input = codec.getInputBuffer(inputIndex) ?: continue
                input.clear()
                input.order(ByteOrder.LITTLE_ENDIAN)

                val maxSamples = input.remaining() / 2
                val count = minOf(maxSamples, samples.size - offsetSamples)
                for (i in 0 until count) input.putShort(samples[offsetSamples + i])

                val pts = totalSamples * 1_000_000L / sampleRate
                codec.queueInputBuffer(inputIndex, 0, count * 2, pts, 0)
                totalSamples += count
                offsetSamples += count
            }
            drain(false)
        }
    }

    fun stop() {
        try {
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val pts = totalSamples * 1_000_000L / sampleRate
                codec.queueInputBuffer(
                    inputIndex, 0, 0, pts,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
            drain(true)
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            if (muxerStarted) {
                try { muxer.stop() } catch (_: Exception) {}
            }
            muxer.release()
        }
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) error("AAC output format changed twice")
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    val output = codec.getOutputBuffer(outIndex)
                    if (output != null && info.size > 0 && muxerStarted) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, output, info)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outIndex, false)
                    if (eos) return
                }
            }
        }
    }
}
