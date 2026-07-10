package com.newton.screenrecorder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import java.util.*

class MainActivity : Activity() {

    private val requestAudio = 101
    private val requestProjection = 102

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var noiseSwitch: Switch

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(ScreenRecordService.EXTRA_STATUS) ?: return
            statusText.text = message
            val recording = intent.getBooleanExtra(ScreenRecordService.EXTRA_RECORDING, false)
            startButton.isEnabled = !recording
            stopButton.isEnabled = recording
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        buildUi()
        registerStatusReceiver()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 56, 32, 32)
            setBackgroundColor(Color.rgb(18, 0, 43))
        }

        val title = TextView(this).apply {
            text = "🎥 تسجيل شاشة الحصة"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 18)
        }

        val subtitle = TextView(this).apply {
            text = "يسجل شاشة الهاتف + صوت الهاتف + صوتك"
            textSize = 17f
            setTextColor(Color.rgb(220, 200, 255))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 28)
        }

        noiseSwitch = Switch(this).apply {
            text = "تقليل الضوضاء وتحسين صوت الميكروفون"
            textSize = 17f
            setTextColor(Color.WHITE)
            isChecked = true
            setPadding(10, 18, 10, 18)
        }

        startButton = Button(this).apply {
            text = "🔴 بدء تسجيل الشاشة"
            textSize = 20f
            setOnClickListener { startFlow() }
        }

        stopButton = Button(this).apply {
            text = "⏹ إنهاء وحفظ الفيديو"
            textSize = 20f
            isEnabled = false
            setOnClickListener {
                val intent = Intent(this@MainActivity, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_STOP
                }
                startService(intent)
            }
        }

        statusText = TextView(this).apply {
            text = "جاهز للتسجيل"
            textSize = 18f
            setTextColor(Color.rgb(255, 235, 59))
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 18)
        }

        val note = TextView(this).apply {
            text = "ملاحظة: بعض التطبيقات تمنع تسجيل صوتها الداخلي. في هذه الحالة سيظل تسجيل الشاشة وصوت الميكروفون يعملان."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(8, 24, 8, 0)
        }

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 12, 0, 12) }

        root.addView(title, lp)
        root.addView(subtitle, lp)
        root.addView(noiseSwitch, lp)
        root.addView(startButton, lp)
        root.addView(stopButton, lp)
        root.addView(statusText, lp)
        root.addView(note, lp)
        setContentView(root)
    }

    private fun startFlow() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }

        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), requestAudio)
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        try {
            startActivityForResult(
                mediaProjectionManager.createScreenCaptureIntent(),
                requestProjection
            )
        } catch (e: Exception) {
            showError("تعذر فتح إذن تسجيل الشاشة: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestAudio) {
            val audioIndex = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
            val audioGranted = audioIndex >= 0 &&
                grantResults.getOrNull(audioIndex) == PackageManager.PERMISSION_GRANTED

            if (audioGranted || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                requestScreenCapture()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("إذن الميكروفون مطلوب")
                    .setMessage("لا يمكن تسجيل صوتك بدون إذن الميكروفون.")
                    .setPositiveButton("فتح الإعدادات") { _, _ ->
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        })
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
        }
    }

    @Deprecated("Kept for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestProjection) return

        if (resultCode != RESULT_OK || data == null) {
            statusText.text = "تم إلغاء إذن تسجيل الشاشة"
            return
        }

        val serviceIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenRecordService.EXTRA_NOISE_REDUCTION, noiseSwitch.isChecked)
        }

        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent)
        else startService(serviceIntent)

        statusText.text = "جاري بدء التسجيل..."
        startButton.isEnabled = false
    }

    private fun registerStatusReceiver() {
        val filter = IntentFilter(ScreenRecordService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle("خطأ")
            .setMessage(message)
            .setPositiveButton("حسنًا", null)
            .show()
    }

    override fun onDestroy() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
