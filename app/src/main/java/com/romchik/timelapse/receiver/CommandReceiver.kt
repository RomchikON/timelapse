package com.romchik.timelapse.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.romchik.timelapse.service.TimelapseService
import com.romchik.timelapse.utils.StorageHelper
import java.util.Calendar

class CommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Received action: $action")

        when (action) {
            "com.romchik.timelapse.START" -> {
                val interval = intent.getIntExtra("interval", 10)
                val duration = intent.getIntExtra("duration", 0)
                val delJpegs = intent.getBooleanExtra("delete_jpegs", true)
                val daily = intent.getBooleanExtra("daily_mode", false)
                val rotHour = intent.getIntExtra("daily_hour", 4)

                val serviceIntent = Intent(context, TimelapseService::class.java).apply {
                    this.action = TimelapseService.ACTION_START
                    putExtra(TimelapseService.EXTRA_INTERVAL_SEC, interval)
                    putExtra(TimelapseService.EXTRA_DURATION_MIN, duration)
                    putExtra(TimelapseService.EXTRA_DELETE_JPEGS, delJpegs)
                    putExtra("daily_mode", daily)
                    putExtra("daily_hour", rotHour)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }

            "com.romchik.timelapse.STOP" -> {
                val serviceIntent = Intent(context, TimelapseService::class.java).apply {
                    this.action = TimelapseService.ACTION_STOP
                }
                context.startService(serviceIntent)
            }

            "com.romchik.timelapse.ENCODE" -> {
                val sessionId = intent.getStringExtra("session_id")
                val isSnapshot = intent.getBooleanExtra("is_snapshot", false)
                val serviceIntent = Intent(context, TimelapseService::class.java).apply {
                    this.action = TimelapseService.ACTION_ENCODE_NOW
                    putExtra(TimelapseService.EXTRA_SESSION_ID, sessionId)
                    putExtra("is_snapshot", isSnapshot)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }

            "com.romchik.timelapse.SCHEDULE" -> {
                val start = intent.getStringExtra("start") ?: "09:00"
                val end = intent.getStringExtra("end") ?: "21:00"
                val interval = intent.getIntExtra("interval", 10)
                val delJpegs = intent.getBooleanExtra("delete_jpegs", true)

                scheduleTimelapse(context, start, end, interval, delJpegs)
            }

            "com.romchik.timelapse.TAKE_SINGLE_PHOTO" -> {
                val serviceIntent = Intent(context, TimelapseService::class.java).apply {
                    this.action = TimelapseService.ACTION_TAKE_PREVIEW
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }

            "com.romchik.timelapse.SET_MODE" -> {
                val mode = intent.getStringExtra("mode") ?: "SMART"
                StorageHelper.setExposureMode(mode)
                Log.i(TAG, "Exposure mode set to: $mode")
            }

            "com.romchik.timelapse.CANCEL_SCHEDULE" -> {
                cancelSchedule(context)
            }

            "com.romchik.timelapse.ALARM_TRIGGER" -> {
                val subAction = intent.getStringExtra("sub_action") ?: "START"
                if (subAction == "START") {
                    val interval = intent.getIntExtra("interval", 10)
                    val durationMin = intent.getIntExtra("duration", 0)
                    val delJpegs = intent.getBooleanExtra("delete_jpegs", true)

                    val serviceIntent = Intent(context, TimelapseService::class.java).apply {
                        this.action = TimelapseService.ACTION_START
                        putExtra(TimelapseService.EXTRA_INTERVAL_SEC, interval)
                        putExtra(TimelapseService.EXTRA_DURATION_MIN, durationMin)
                        putExtra(TimelapseService.EXTRA_DELETE_JPEGS, delJpegs)
                    }
                    ContextCompat.startForegroundService(context, serviceIntent)
                } else if (subAction == "STOP") {
                    val serviceIntent = Intent(context, TimelapseService::class.java).apply {
                        this.action = TimelapseService.ACTION_STOP
                    }
                    context.startService(serviceIntent)
                }
            }
        }
    }

    companion object {
        private const val TAG = "CommandReceiver"
        private const val ALARM_REQ_START = 2001
        private const val ALARM_REQ_STOP = 2002

        fun scheduleTimelapse(
            context: Context,
            startTimeStr: String,
            endTimeStr: String,
            intervalSec: Int,
            deleteJpegs: Boolean
        ) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val (startH, startM) = parseTime(startTimeStr)
            val (endH, endM) = parseTime(endTimeStr)

            val now = Calendar.getInstance()

            val startCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, startH)
                set(Calendar.MINUTE, startM)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now)) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val endCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, endH)
                set(Calendar.MINUTE, endM)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(startCal)) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val durationMinutes = ((endCal.timeInMillis - startCal.timeInMillis) / (60 * 1000L)).toInt()

            val startIntent = Intent(context, CommandReceiver::class.java).apply {
                action = "com.romchik.timelapse.ALARM_TRIGGER"
                putExtra("sub_action", "START")
                putExtra("interval", intervalSec)
                putExtra("duration", durationMinutes)
                putExtra("delete_jpegs", deleteJpegs)
            }
            val startPending = PendingIntent.getBroadcast(
                context,
                ALARM_REQ_START,
                startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    startCal.timeInMillis,
                    startPending
                )
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, startCal.timeInMillis, startPending)
            }

            Log.i(TAG, "Scheduled start at ${startCal.time} (duration: $durationMinutes min)")
        }

        fun cancelSchedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val startIntent = Intent(context, CommandReceiver::class.java).apply {
                action = "com.romchik.timelapse.ALARM_TRIGGER"
            }
            val startPending = PendingIntent.getBroadcast(
                context,
                ALARM_REQ_START,
                startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(startPending)
            Log.i(TAG, "Cancelled schedule")
        }

        private fun parseTime(timeStr: String): Pair<Int, Int> {
            val parts = timeStr.trim().split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: 9
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return Pair(h, m)
        }
    }
}
