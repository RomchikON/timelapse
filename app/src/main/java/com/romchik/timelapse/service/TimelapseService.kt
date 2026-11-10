package com.romchik.timelapse.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.romchik.timelapse.R
import com.romchik.timelapse.camera.TimelapseCameraEngine
import com.romchik.timelapse.encoder.VideoEncoder
import com.romchik.timelapse.ui.MainActivity
import com.romchik.timelapse.utils.BatteryHelper
import com.romchik.timelapse.utils.StorageHelper
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

class TimelapseService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var recordingJob: Job? = null
    private var encodingJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var cameraEngine: TimelapseCameraEngine? = null

    private var currentState = STATE_IDLE
    private var currentSessionId: String? = null
    private var intervalSec = 15
    private var durationMinutes = 0
    private var deleteJpegs = true
    private var frameCount = 0
    private var startTimeMs = 0L
    private var endTimeMs = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Timelapse::WakeLock")
        cameraEngine = TimelapseCameraEngine(this).apply { start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_STICKY

        when (action) {
            ACTION_START -> {
                val interval = intent.getIntExtra(EXTRA_INTERVAL_SEC, 15)
                val duration = intent.getIntExtra(EXTRA_DURATION_MIN, 0)
                val delJpegs = intent.getBooleanExtra(EXTRA_DELETE_JPEGS, true)
                startRecording(interval, duration, delJpegs)
            }
            ACTION_STOP -> {
                stopRecording(andEncode = true)
            }
            ACTION_TAKE_PREVIEW -> {
                takePreviewSnapshot()
            }
            ACTION_ENCODE_NOW -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                triggerEncode(sessionId)
            }
        }

        return START_STICKY
    }

    private fun takePreviewSnapshot() {
        if (currentState == STATE_RECORDING) {
            serviceScope.launch {
                cameraEngine?.capturePhoto(
                    targetFile = StorageHelper.lastFrameFile,
                    intervalSec = intervalSec,
                    isPreview = true
                ) { success ->
                    Log.i(TAG, "Preview snapshot while recording: $success")
                }
            }
            return
        }

        wakeLock?.acquire(20_000L)
        startForegroundNotification("Предпросмотр", "Делается снимок камеры...")

        serviceScope.launch {
            val completer = CompletableDeferred<Boolean>()
            cameraEngine?.capturePhoto(
                targetFile = StorageHelper.lastFrameFile,
                intervalSec = intervalSec,
                isPreview = true
            ) { success ->
                completer.complete(success)
            }
            val result = withTimeoutOrNull(16_000L) { completer.await() } ?: false
            Log.i(TAG, "Preview snapshot completed: $result")

            withContext(Dispatchers.Main) {
                if (currentState == STATE_IDLE) {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun startRecording(interval: Int, duration: Int, delJpegs: Boolean) {
        if (currentState == STATE_RECORDING) {
            Log.w(TAG, "Already recording")
            return
        }

        intervalSec = interval.coerceAtLeast(1)
        durationMinutes = duration
        deleteJpegs = delJpegs
        frameCount = 0
        startTimeMs = System.currentTimeMillis()
        endTimeMs = if (durationMinutes > 0) startTimeMs + (durationMinutes * 60 * 1000L) else 0L
        currentSessionId = StorageHelper.generateSessionId()
        currentState = STATE_RECORDING
        isRecordingActive = true

        wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours max safeguard

        startForegroundNotification("Таймлапс активен", "Кадров: 0")

        broadcastStatus()

        recordingJob?.cancel()
        recordingJob = serviceScope.launch {
            val sessionDir = StorageHelper.getSessionDir(currentSessionId!!)

            while (isActive && currentState == STATE_RECORDING) {
                // Check if duration expired
                if (endTimeMs > 0 && System.currentTimeMillis() >= endTimeMs) {
                    Log.i(TAG, "Duration expired, finishing session")
                    withContext(Dispatchers.Main) {
                        stopRecording(andEncode = true)
                    }
                    break
                }

                val frameStart = System.currentTimeMillis()
                val targetFile = File(sessionDir, String.format(Locale.US, "frame_%05d.jpg", frameCount))

                val captureCompleter = CompletableDeferred<Boolean>()
                cameraEngine?.capturePhoto(
                    targetFile = targetFile,
                    intervalSec = intervalSec,
                    isPreview = false
                ) { success ->
                    captureCompleter.complete(success)
                }

                val success = captureCompleter.await()
                if (success) {
                    frameCount++
                    updateNotification("Кадров: $frameCount (${getFormattedVideoDuration()})")
                    broadcastStatus()
                }

                val elapsed = System.currentTimeMillis() - frameStart
                val remainingSleep = (intervalSec * 1000L - elapsed).coerceAtLeast(100L)
                delay(remainingSleep)
            }
        }
    }

    private fun stopRecording(andEncode: Boolean) {
        isRecordingActive = false
        if (currentState != STATE_RECORDING && currentState != STATE_ENCODING) {
            return
        }

        recordingJob?.cancel()
        recordingJob = null

        val finishedSession = currentSessionId
        val finalFrames = frameCount

        if (andEncode && finishedSession != null && finalFrames > 0) {
            triggerEncode(finishedSession)
        } else {
            currentState = STATE_IDLE
            broadcastStatus()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun triggerEncode(targetSessionId: String?) {
        val sessionId = targetSessionId ?: currentSessionId ?: return
        val sessionDir = StorageHelper.getSessionDir(sessionId)

        currentState = STATE_ENCODING
        broadcastStatus()
        updateNotification("Сборка MP4 видео...")

        encodingJob?.cancel()
        encodingJob = serviceScope.launch(Dispatchers.Default) {
            val outputFile = File(StorageHelper.videosDir, "timelapse_$sessionId.mp4")

            val success = VideoEncoder.encodeSession(
                context = this@TimelapseService,
                sessionDir = sessionDir,
                outputVideoFile = outputFile,
                width = 1280,
                height = 720,
                frameRate = 30,
                bitRate = 3_000_000,
                deleteJpegsAfterSuccess = deleteJpegs,
                onProgress = { current, total ->
                    updateNotification("Сборка MP4: $current/$total")
                }
            )

            Log.i(TAG, "Encoding finished with success: $success")

            withContext(Dispatchers.Main) {
                currentState = STATE_IDLE
                broadcastStatus()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun broadcastStatus() {
        val battery = BatteryHelper.getBatteryInfo(this)
        StorageHelper.writeStatus(
            state = currentState,
            sessionId = currentSessionId,
            intervalSec = intervalSec,
            durationMinutes = durationMinutes,
            framesCount = frameCount,
            startTimeMs = startTimeMs,
            endTimeMs = endTimeMs,
            deleteJpegs = deleteJpegs,
            batteryInfo = battery
        )

        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra("state", currentState)
            putExtra("frames", frameCount)
            putExtra("session_id", currentSessionId)
        }
        sendBroadcast(intent)
    }

    private fun getFormattedVideoDuration(): String {
        val totalSec = frameCount / 30
        val mins = totalSec / 60
        val secs = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Съемка таймлапса",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Фоновая служба съемки и сборки видео"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification(title: String, content: String) {
        val notification = buildNotification(title, content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(content: String) {
        val notification = buildNotification("Таймлапс активен", content)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_camera)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        isRecordingActive = false
        recordingJob?.cancel()
        encodingJob?.cancel()
        serviceScope.cancel()
        cameraEngine?.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile
        var isRecordingActive: Boolean = false

        const val TAG = "TimelapseService"
        const val CHANNEL_ID = "timelapse_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.romchik.timelapse.ACTION_START"
        const val ACTION_STOP = "com.romchik.timelapse.ACTION_STOP"
        const val ACTION_TAKE_PREVIEW = "com.romchik.timelapse.ACTION_TAKE_PREVIEW"
        const val ACTION_ENCODE_NOW = "com.romchik.timelapse.ACTION_ENCODE_NOW"
        const val ACTION_STATUS_UPDATE = "com.romchik.timelapse.STATUS_UPDATE"

        const val EXTRA_INTERVAL_SEC = "extra_interval_sec"
        const val EXTRA_DURATION_MIN = "extra_duration_min"
        const val EXTRA_DELETE_JPEGS = "extra_delete_jpegs"
        const val EXTRA_SESSION_ID = "extra_session_id"

        const val STATE_IDLE = "IDLE"
        const val STATE_RECORDING = "RECORDING"
        const val STATE_ENCODING = "ENCODING"
    }
}
