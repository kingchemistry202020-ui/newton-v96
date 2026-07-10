package com.newton.screenrecorder

import android.app.*
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "com.newton.screenrecorder.START"
        const val ACTION_STOP = "com.newton.screenrecorder.STOP"
        const val ACTION_STATUS = "com.newton.screenrecorder.STATUS"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_NOISE_REDUCTION = "noiseReduction"
        const val EXTRA_STATUS = "status"
        const val EXTRA_RECORDING = "recording"

        private const val CHANNEL_ID = "newton_recording"
        private const val NOTIFICATION_ID = 9001
    }

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var recording = false

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var audioMixer: AudioCaptureMixer? = null

    private lateinit var tempVideo: File
    private lateinit var tempAudio: File
    private lateinit var tempFinal: File

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!recording) {
                    startInForeground("جاري تجهيز التسجيل...")
                    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                    val resultData = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_RESULT_DATA)
                    }
                    val noiseReduction = intent.getBooleanExtra(EXTRA_NOISE_REDUCTION, true)

                    if (resultCode == Activity.RESULT_OK && resultData != null) {
                        executor.execute {
                            try {
                                startRecording(resultCode, resultData, noiseReduction)
                            } catch (e: Exception) {
                                broadcastStatus("فشل بدء التسجيل: ${e.message}", false)
                                stopSelf()
                            }
                        }
                    } else {
                        broadcastStatus("إذن تسجيل الشاشة غير صالح", false)
                        stopSelf()
                    }
                }
            }
            ACTION_STOP -> executor.execute { stopRecordingAndSave() }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startRecording(
        resultCode: Int,
        resultData: Intent,
        noiseReduction: Boolean
    ) {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
        requireNotNull(mediaProjection) { "تعذر إنشاء MediaProjection" }

        mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (recording) executor.execute { stopRecordingAndSave() }
            }
        }, null)

        val cache = cacheDir
        tempVideo = File(cache, "newton_video_${System.currentTimeMillis()}.mp4")
        tempAudio = File(cache, "newton_audio_${System.currentTimeMillis()}.m4a")
        tempFinal = File(cache, "newton_final_${System.currentTimeMillis()}.mp4")

        val dm = resources.displayMetrics
        val sourceWidth = dm.widthPixels
        val sourceHeight = dm.heightPixels
        val density = dm.densityDpi
        val (width, height) = fitSize(sourceWidth, sourceHeight, 1920)

        mediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(8_000_000)
            setVideoFrameRate(30)
            setVideoSize(width, height)
            setOutputFile(tempVideo.absolutePath)
            prepare()
        }

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "NewtonScreen",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder!!.surface,
            null,
            null
        )

        audioMixer = AudioCaptureMixer(
            mediaProjection = mediaProjection!!,
            outputFile = tempAudio,
            noiseReductionEnabled = noiseReduction
        )

        mediaRecorder!!.start()
        audioMixer!!.start()
        recording = true
        updateNotification("يتم الآن تسجيل الشاشة والصوت")
        broadcastStatus("🔴 التسجيل يعمل الآن", true)
    }

    private fun stopRecordingAndSave() {
        if (!recording) return
        recording = false
        broadcastStatus("جاري إنهاء الفيديو وحفظه...", false)
        updateNotification("جاري حفظ التسجيل...")

        try { audioMixer?.stop() } catch (_: Exception) {}
        try { mediaRecorder?.stop() } catch (_: Exception) {}
        try { mediaRecorder?.reset() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null

        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null

        try {
            if (tempAudio.exists() && tempAudio.length() > 0) {
                MuxerUtils.combineVideoAndAudio(tempVideo, tempAudio, tempFinal)
            } else {
                tempVideo.copyTo(tempFinal, overwrite = true)
            }

            val uri = publishToMovies(tempFinal)
            broadcastStatus("✅ تم حفظ الفيديو في Movies/NewtonRecordings", false)
            updateNotification("تم حفظ التسجيل بنجاح")
            sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
        } catch (e: Exception) {
            broadcastStatus("تعذر دمج/حفظ الفيديو: ${e.message}", false)
            updateNotification("حدث خطأ أثناء الحفظ")
        } finally {
            listOf(tempVideo, tempAudio, tempFinal).forEach {
                try { if (::tempVideo.isInitialized || it.exists()) it.delete() } catch (_: Exception) {}
            }
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun publishToMovies(file: File): Uri {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val name = "Newton_Lesson_$stamp.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/NewtonRecordings")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: error("تعذر إنشاء ملف الفيديو")

        contentResolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: error("تعذر فتح ملف الحفظ")

        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun fitSize(width: Int, height: Int, maxLongSide: Int): Pair<Int, Int> {
        val longSide = maxOf(width, height)
        if (longSide <= maxLongSide) return even(width) to even(height)
        val scale = maxLongSide.toFloat() / longSide.toFloat()
        return even((width * scale).roundToInt()) to even((height * scale).roundToInt())
    }

    private fun even(value: Int): Int = if (value % 2 == 0) value else value - 1

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "تسجيل شاشة نيوتن",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            10,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Newton Screen Recorder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(recording)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "إنهاء التسجيل",
                    stopPending
                ).build()
            )
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun broadcastStatus(message: String, isRecording: Boolean) {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, message)
            putExtra(EXTRA_RECORDING, isRecording)
        })
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
