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

    data class CaptureMetadata(
        val iso: Int = 100,
        val exposureNs: Long = 33_333_333L,
        val luma: Int = 100,
        val mode: String = "AUTO"
    ) {
        val exposureFormatted: String
            get() {
                val sec = exposureNs / 1_000_000_000.0
                return if (sec >= 0.3) {
                    String.format(Locale.US, "%.2f сек", sec)
                } else {
                    val denom = (1.0 / sec.coerceAtLeast(0.0001)).toInt().coerceAtLeast(1)
                    "1/$denom сек"
                }
            }
    }

    @Volatile
    var lastCaptureMeta: CaptureMetadata = CaptureMetadata()

    fun getExposureMode(): String {
        return try {
            val status = readStatus()
            status?.optString("user_exposure_mode", "SMART") ?: "SMART"
        } catch (e: Exception) {
            "SMART"
        }
    }

    fun setExposureMode(mode: String) {
        try {
            val existing = readStatus() ?: JSONObject()
            existing.put("user_exposure_mode", mode.uppercase(Locale.US))
            existing.put("last_updated", System.currentTimeMillis())
            statusFile.writeText(existing.toString(2))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

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
        return SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
    }

    fun generateDailySessionId(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    fun updateLastCaptureMeta(meta: CaptureMetadata) {
        lastCaptureMeta = meta
        try {
            val existing = readStatus() ?: JSONObject()
            existing.put("last_iso", meta.iso)
            existing.put("last_exposure_ns", meta.exposureNs)
            existing.put("last_exposure_str", meta.exposureFormatted)
            existing.put("last_luma", meta.luma)
            existing.put("exposure_mode", meta.mode)
            existing.put("last_updated", System.currentTimeMillis())
            statusFile.writeText(existing.toString(2))
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
            val meta = lastCaptureMeta
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
                put("user_exposure_mode", getExposureMode())
                put("last_iso", meta.iso)
                put("last_exposure_ns", meta.exposureNs)
                put("last_exposure_str", meta.exposureFormatted)
                put("last_luma", meta.luma)
                put("exposure_mode", meta.mode)
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

    /** Persistent CSV diary of battery / power state and capture hiccups, survives restarts and Android stat resets. */
    val batteryLogFile: File
        get() = File(baseDir, "battery_log.csv")

    private const val BATTERY_LOG_HEADER =
        "time,level_pct,charging,plug,temp_c,voltage_mv,doze,power_save,frames,note"
    private const val BATTERY_LOG_MAX_BYTES = 2L * 1024 * 1024

    @Synchronized
    fun appendBatteryLog(row: String) {
        try {
            val f = batteryLogFile
            if (f.exists() && f.length() > BATTERY_LOG_MAX_BYTES) {
                val old = File(f.parentFile, "battery_log.old.csv")
                old.delete()
                f.renameTo(old)
            }
            if (!f.exists() || f.length() == 0L) {
                f.writeText(BATTERY_LOG_HEADER + "\n")
            }
            f.appendText(row + "\n")
        } catch (e: Exception) {
            e.printStackTrace()
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
