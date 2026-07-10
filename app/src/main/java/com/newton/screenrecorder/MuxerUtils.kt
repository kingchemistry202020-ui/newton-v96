package com.newton.screenrecorder

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

object MuxerUtils {

    fun combineVideoAndAudio(videoFile: File, audioFile: File, outputFile: File) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        videoExtractor.setDataSource(videoFile.absolutePath)
        audioExtractor.setDataSource(audioFile.absolutePath)

        val videoTrack = findTrack(videoExtractor, "video/")
        val audioTrack = findTrack(audioExtractor, "audio/")
        require(videoTrack >= 0) { "No video track found" }
        require(audioTrack >= 0) { "No audio track found" }

        videoExtractor.selectTrack(videoTrack)
        audioExtractor.selectTrack(audioTrack)

        val videoFormat = videoExtractor.getTrackFormat(videoTrack)
        val audioFormat = audioExtractor.getTrackFormat(audioTrack)

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outVideoTrack = muxer.addTrack(videoFormat)
        val outAudioTrack = muxer.addTrack(audioFormat)
        muxer.start()

        val maxInput = max(
            safeMaxInputSize(videoFormat),
            safeMaxInputSize(audioFormat)
        ).coerceAtLeast(2 * 1024 * 1024)

        val buffer = ByteBuffer.allocateDirect(maxInput)
        val info = MediaCodec.BufferInfo()

        var videoDone = false
        var audioDone = false

        while (!videoDone || !audioDone) {
            val videoTime = if (videoDone) Long.MAX_VALUE else videoExtractor.sampleTime
            val audioTime = if (audioDone) Long.MAX_VALUE else audioExtractor.sampleTime

            if (videoTime < 0) videoDone = true
            if (audioTime < 0) audioDone = true
            if (videoDone && audioDone) break

            val useVideo = !videoDone && (audioDone || videoTime <= audioTime)
            val extractor = if (useVideo) videoExtractor else audioExtractor
            val track = if (useVideo) outVideoTrack else outAudioTrack

            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
                if (useVideo) videoDone = true else audioDone = true
                continue
            }

            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0)
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(track, buffer, info)
            extractor.advance()
        }

        try { muxer.stop() } finally {
            muxer.release()
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    private fun safeMaxInputSize(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else 0
}
