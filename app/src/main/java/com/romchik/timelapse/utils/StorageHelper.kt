package com.romchik.timelapse.utils

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object StorageHelper {

    private val baseDir: File
        get() {
            val root = Environment.getExternalStorageDirectory()
            val dir = File(root, "Timelapse")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    val sessionsDir: File
        get() {
            val dir = File(baseDir, "sessions")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    val videosDir: File
        get() {
            val dir = File(baseDir, "videos")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    val statusFile: File
        get() {
            val f = File(baseDir, "status.json")
            f.parentFile?.mkdirs()
            return f
        }

    val lastFrameFile: File
        get() {
            val f = File(baseDir, "last_frame.jpg")
            f.parentFile?.mkdirs()
            return f
        }

    fun getSessionDir(sessionId: String): File {
        val dir = File(sessionsDir, sessionId)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun generateSessionId(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }

    fun writeStatus(
        state: String,
        sessionId: String?,
        intervalSec: Int,
        durationMinutes: Int,
        framesCount: Int,
        startTimeMs: Long,
        endTimeMs: Long,
        deleteJpegs: Boolean,
        batteryInfo: BatteryHelper.BatteryInfo,
        scheduledStart: String? = null,
        scheduledEnd: String? = null
    ) {
        try {
            val json = JSONObject().apply {
                put("state", state)
                put("session_id", sessionId ?: "")
                put("interval_sec", intervalSec)
                put("duration_minutes", durationMinutes)
                put("frames_captured", framesCount)
                put("video_duration_sec", String.format(Locale.US, "%.1f", framesCount / 30.0))
                put("started_at_ms", startTimeMs)
                put("end_time_ms", endTimeMs)
                put("delete_jpegs_after_encode", deleteJpegs)
                put("battery_level", batteryInfo.level)
                put("battery_temp_c", batteryInfo.temperatureCelsius)
                put("is_charging", batteryInfo.isCharging)
                put("free_storage_gb", String.format(Locale.US, "%.1f", getAvailableStorageGb()))
                put("scheduled_start", scheduledStart ?: "")
                put("scheduled_end", scheduledEnd ?: "")
                put("last_updated", System.currentTimeMillis())
            }
            statusFile.writeText(json.toString(2))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun readStatus(): JSONObject? {
        return try {
            if (statusFile.exists()) {
                JSONObject(statusFile.readText())
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun getAvailableStorageGb(): Double {
        return try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val bytesAvailable = stat.availableBlocksLong * stat.blockSizeLong
            bytesAvailable.toDouble() / (1024.0 * 1024.0 * 1024.0)
        } catch (e: Exception) {
            0.0
        }
    }

    fun scanMediaFile(context: Context, file: File) {
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("video/mp4"),
                null
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
