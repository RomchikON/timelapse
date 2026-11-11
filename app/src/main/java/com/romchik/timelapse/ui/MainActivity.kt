package com.romchik.timelapse.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.romchik.timelapse.R
import com.romchik.timelapse.camera.TimelapseCameraEngine
import com.romchik.timelapse.databinding.ActivityMainBinding
import com.romchik.timelapse.receiver.CommandReceiver
import com.romchik.timelapse.service.TimelapseService
import com.romchik.timelapse.utils.BatteryHelper
import com.romchik.timelapse.utils.StorageHelper
import kotlinx.coroutines.*
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var selectedIntervalSec = 10
    private var selectedDurationMinutes = 0 // 0 = unlimited
    private var isScheduleMode = false
    private var scheduleStart = "09:00"
    private var scheduleEnd = "21:00"

    private var currentState = TimelapseService.STATE_IDLE

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateUiFromStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        requestPermissionsIfNeeded()
        requestBatteryOptimizationExemption()

        handlePreviewIntent(intent)

        lifecycleScope.launch {
            while (isActive) {
                updateUiFromStatus()
                delay(1000)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(statusReceiver, IntentFilter(TimelapseService.ACTION_STATUS_UPDATE))
        updateUiFromStatus()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePreviewIntent(intent)
    }

    private fun handlePreviewIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("start_recording", false) == true) {
            val interval = intent.getIntExtra("interval", 10)
            val duration = intent.getIntExtra("duration", 0)
            val delJpegs = intent.getBooleanExtra("delete_jpegs", true)
            val daily = intent.getBooleanExtra("daily_mode", false)
            val rotHour = intent.getIntExtra("daily_hour", 4)
            intent.removeExtra("start_recording")

            wakeUpWindowForCamera()

            lifecycleScope.launch(Dispatchers.Main) {
                delay(250)
                val serviceIntent = Intent(this@MainActivity, TimelapseService::class.java).apply {
                    action = TimelapseService.ACTION_START
                    putExtra(TimelapseService.EXTRA_INTERVAL_SEC, interval)
                    putExtra(TimelapseService.EXTRA_DURATION_MIN, duration)
                    putExtra(TimelapseService.EXTRA_DELETE_JPEGS, delJpegs)
                    putExtra("daily_mode", daily)
                    putExtra("daily_hour", rotHour)
                }
                ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                delay(1200)
                moveTaskToBack(true)
            }
            return
        }

        if (intent?.getBooleanExtra("take_preview", false) == true) {
            val shouldClose = intent.getBooleanExtra("close_after_preview", true)
            val customMaxExp = if (intent.hasExtra("max_exposure_sec")) intent.getFloatExtra("max_exposure_sec", 4.5f).toDouble() else null
            intent.removeExtra("take_preview")
            intent.removeExtra("max_exposure_sec")

            wakeUpWindowForCamera()

            lifecycleScope.launch(Dispatchers.Main) {
                delay(350)
                val status = StorageHelper.readStatus()
                val currentInterval = status?.optInt("interval_sec", selectedIntervalSec) ?: selectedIntervalSec

                if (TimelapseService.isRecordingActive) {
                    // Service already owns the camera in RAM — ask it to take preview
                    val serviceIntent = Intent(this@MainActivity, TimelapseService::class.java).apply {
                        action = TimelapseService.ACTION_TAKE_PREVIEW
                        if (customMaxExp != null) {
                            putExtra("max_exposure_sec", customMaxExp)
                        }
                    }
                    ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                    delay(1000)
                } else {
                    val engine = TimelapseCameraEngine(this@MainActivity).apply { start() }
                    val completer = CompletableDeferred<Boolean>()
                    engine.capturePhoto(
                        targetFile = StorageHelper.lastFrameFile,
                        intervalSec = currentInterval,
                        isPreview = true,
                        maxExposureCapSec = customMaxExp
                    ) { success ->
                        completer.complete(success)
                    }
                    val waitTimeout = ((customMaxExp ?: 4.5) * 1000L + 8000L).toLong().coerceIn(12_000L, 45_000L)
                    withTimeoutOrNull(waitTimeout) { completer.await() }
                    engine.stop()
                }

                if (shouldClose && !TimelapseService.isRecordingActive) {
                    moveTaskToBack(true)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeUpWindowForCamera() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val screenLock = pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Timelapse::ScreenWake"
            )
            screenLock.acquire(10_000L)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun setupListeners() {
        // Interval Chips
        binding.chipGroupInterval.setOnCheckedChangeListener { _, checkedId ->
            selectedIntervalSec = when (checkedId) {
                R.id.chipInt5 -> 5
                R.id.chipInt10 -> 10
                R.id.chipInt15 -> 15
                R.id.chipInt30 -> 30
                R.id.chipInt60 -> 60
                else -> 15
            }
        }

        // Mode Radio
        binding.rgMode.setOnCheckedChangeListener { _, checkedId ->
            isScheduleMode = checkedId == R.id.rbModeSchedule
            binding.layoutTimerDuration.visibility = if (isScheduleMode) View.GONE else View.VISIBLE
            binding.layoutScheduleTimes.visibility = if (isScheduleMode) View.VISIBLE else View.GONE
        }

        // Duration Chips
        binding.chipGroupDuration.setOnCheckedChangeListener { _, checkedId ->
            selectedDurationMinutes = when (checkedId) {
                R.id.chipDurInfinite -> 0
                R.id.chipDur2h -> 2 * 60
                R.id.chipDur4h -> 4 * 60
                R.id.chipDur8h -> 8 * 60
                R.id.chipDur12h -> 12 * 60
                R.id.chipDur24h -> 24 * 60
                else -> 0
            }
        }

        // Schedule Time Pickers
        binding.btnStartTime.setOnClickListener {
            showTimePicker(scheduleStart) { time ->
                scheduleStart = time
                binding.btnStartTime.text = "Старт: $scheduleStart"
            }
        }

        binding.btnEndTime.setOnClickListener {
            showTimePicker(scheduleEnd) { time ->
                scheduleEnd = time
                binding.btnEndTime.text = "Конец: $scheduleEnd"
            }
        }

        // Big Start/Stop Button
        binding.btnStartStop.setOnClickListener {
            if (currentState == TimelapseService.STATE_RECORDING) {
                stopTimelapse()
            } else if (currentState == TimelapseService.STATE_IDLE) {
                startTimelapse()
            }
        }

        // Encode Now Button
        binding.btnEncodeNow.setOnClickListener {
            val sessions = StorageHelper.sessionsDir.listFiles { f -> f.isDirectory }
            if (!sessions.isNullOrEmpty()) {
                val latestSession = sessions.maxByOrNull { it.name }
                if (latestSession != null) {
                    val serviceIntent = Intent(this, TimelapseService::class.java).apply {
                        action = TimelapseService.ACTION_ENCODE_NOW
                        putExtra(TimelapseService.EXTRA_SESSION_ID, latestSession.name)
                        putExtra(TimelapseService.EXTRA_DELETE_JPEGS, binding.switchDeleteJpegs.isChecked)
                    }
                    ContextCompat.startForegroundService(this, serviceIntent)
                    Toast.makeText(this, "Запущена сборка видео...", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Нет сохраненных сессий для сборки", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startTimelapse() {
        if (!hasCameraPermission()) {
            requestPermissionsIfNeeded()
            return
        }

        val deleteJpegs = binding.switchDeleteJpegs.isChecked

        if (isScheduleMode) {
            CommandReceiver.scheduleTimelapse(
                context = this,
                startTimeStr = scheduleStart,
                endTimeStr = scheduleEnd,
                intervalSec = selectedIntervalSec,
                deleteJpegs = deleteJpegs
            )
            Toast.makeText(this, "Съемка запланирована с $scheduleStart до $scheduleEnd", Toast.LENGTH_LONG).show()
            binding.tvLiveStatusBadge.text = "ПО РАСПИСАНИЮ"
            binding.tvLiveStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_warning))
        } else {
            val serviceIntent = Intent(this, TimelapseService::class.java).apply {
                action = TimelapseService.ACTION_START
                putExtra(TimelapseService.EXTRA_INTERVAL_SEC, selectedIntervalSec)
                putExtra(TimelapseService.EXTRA_DURATION_MIN, selectedDurationMinutes)
                putExtra(TimelapseService.EXTRA_DELETE_JPEGS, deleteJpegs)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        }
    }

    private fun stopTimelapse() {
        val serviceIntent = Intent(this, TimelapseService::class.java).apply {
            action = TimelapseService.ACTION_STOP
        }
        startService(serviceIntent)
        Toast.makeText(this, "Остановка съемки, сборка MP4...", Toast.LENGTH_SHORT).show()
    }

    private fun updateUiFromStatus() {
        val status = StorageHelper.readStatus()
        currentState = status?.optString("state", TimelapseService.STATE_IDLE) ?: TimelapseService.STATE_IDLE
        val frames = status?.optInt("frames_captured", 0) ?: 0

        // Frames and duration
        binding.tvFrameCount.text = frames.toString()
        val totalSec = frames / 30
        val mins = totalSec / 60
        val secs = totalSec % 60
        binding.tvVideoDuration.text = String.format(Locale.US, "%02d:%02d", mins, secs)

        // Telemetry
        val battery = BatteryHelper.getBatteryInfo(this)
        val chargingIcon = if (battery.isCharging) "⚡" else "🔋"
        binding.tvBattery.text = "$chargingIcon ${battery.level}%"
        binding.tvTemperature.text = String.format(Locale.US, "🌡 %.1f°C", battery.temperatureCelsius)
        binding.tvStorage.text = String.format(Locale.US, "💾 %.1f GB", StorageHelper.getAvailableStorageGb())

        // Badge & Button state
        when (currentState) {
            TimelapseService.STATE_RECORDING -> {
                binding.tvLiveStatusBadge.text = "СЪЕМКА"
                binding.tvLiveStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_stop))
                binding.btnStartStop.text = "ОСТАНОВИТЬ СЪЕМКУ"
                binding.btnStartStop.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_stop)
                binding.btnStartStop.isEnabled = true
            }
            TimelapseService.STATE_ENCODING -> {
                binding.tvLiveStatusBadge.text = "СБОРКА MP4"
                binding.tvLiveStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.primary_dark))
                binding.btnStartStop.text = "СОЗДАНИЕ ВИДЕО..."
                binding.btnStartStop.backgroundTintList = ContextCompat.getColorStateList(this, R.color.card_border)
                binding.btnStartStop.isEnabled = false
            }
            else -> {
                binding.tvLiveStatusBadge.text = "ГОТОВ"
                binding.tvLiveStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.primary))
                binding.btnStartStop.text = "НАЧАТЬ СЪЕМКУ"
                binding.btnStartStop.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
                binding.btnStartStop.isEnabled = true
            }
        }

        // Check unencoded sessions
        val unencodedSessions = StorageHelper.sessionsDir.listFiles { f -> f.isDirectory && f.list()?.isNotEmpty() == true }
        if (!unencodedSessions.isNullOrEmpty() && currentState == TimelapseService.STATE_IDLE) {
            binding.btnEncodeNow.visibility = View.VISIBLE
        } else {
            binding.btnEncodeNow.visibility = View.GONE
        }
    }

    private fun showTimePicker(currentTime: String, onTimeSelected: (String) -> Unit) {
        val parts = currentTime.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 9
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0

        TimePickerDialog(this, { _, selectedH, selectedM ->
            onTimeSelected(String.format(Locale.US, "%02d:%02d", selectedH, selectedM))
        }, h, m, true).show()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Ignore if not allowed
                }
            }
        }
    }
}
