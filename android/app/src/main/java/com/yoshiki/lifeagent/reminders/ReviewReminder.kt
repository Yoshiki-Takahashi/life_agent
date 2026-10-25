package com.yoshiki.lifeagent.reminders

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yoshiki.lifeagent.MainActivity
import com.yoshiki.lifeagent.ui.nextReviewTime
import java.time.Instant
import java.time.ZoneId

class ReviewReminder(private val context: Context) {
    private val prefs = context.getSharedPreferences("review-reminder", Context.MODE_PRIVATE)
    private val alarm = context.getSystemService(AlarmManager::class.java)
    private fun pending() = PendingIntent.getBroadcast(context, 10,
        Intent(context, ReviewReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun setUser(uid: String?) {
        if (prefs.getString("active", null) == uid) { schedule(); return }
        alarm.cancel(pending())
        context.getSystemService(NotificationManager::class.java).cancel(10)
        prefs.edit().putString("active", uid).apply()
        schedule()
    }
    fun enabled(uid: String) = prefs.getBoolean("$uid-enabled", false)
    fun weekly(uid: String) = prefs.getBoolean("$uid-weekly", false)
    fun zone(uid: String) = prefs.getString("$uid-zone", ZoneId.systemDefault().id)!!
    fun save(uid: String, enabled: Boolean, weekly: Boolean, zone: String) {
        ZoneId.of(zone)
        prefs.edit().putBoolean("$uid-enabled", enabled).putBoolean("$uid-weekly", weekly)
            .putString("$uid-zone", zone).apply()
        alarm.cancel(pending())
        if (!enabled) context.getSystemService(NotificationManager::class.java).cancel(10)
        schedule()
    }
    fun schedule() {
        val uid = prefs.getString("active", null) ?: return
        if (!enabled(uid)) return
        val next = nextReviewTime(Instant.now(), ZoneId.of(zone(uid)), weekly(uid))
        // Inexact alarms are sufficient for reflection; no exact-alarm permission is requested.
        alarm.set(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pending())
    }
    fun deliver() {
        val uid = prefs.getString("active", null) ?: return
        if (!enabled(uid)) return
        val now = Instant.now().atZone(ZoneId.of(zone(uid)))
        val key = now.toLocalDate().toString()
        if (prefs.getString("$uid-last", null) == key) { schedule(); return }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel("review", "振り返り", NotificationManager.IMPORTANCE_DEFAULT))
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            schedule()
            return
        }
        val open = PendingIntent.getActivity(context, 10, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "review")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("目標を振り返りましょう")
            .setContentText("Goal詳細から期間内の記録と次の行動を確認できます。")
            .setContentIntent(open).setAutoCancel(true).build()
        // Persist before posting: duplicate broadcasts never produce a second alert.
        prefs.edit().putString("$uid-last", key).commit()
        NotificationManagerCompat.from(context).notify(10, notification)
        schedule()
    }
}

class ReviewReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminder = ReviewReminder(context)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED) reminder.schedule()
        else reminder.deliver()
    }
}
