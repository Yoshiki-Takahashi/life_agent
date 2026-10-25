package com.yoshiki.lifeagent

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.yoshiki.lifeagent.reminders.ReviewReminder
import org.junit.Assert.*
import org.junit.Test

class ReviewReminderTest {
    @Test fun denialDeduplicationDisableAndLogout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("test-$name", mode)
        }
        val prefs = context.getSharedPreferences("review-reminder", Context.MODE_PRIVATE)
        val manager = base.getSystemService(NotificationManager::class.java)
        val reminder = ReviewReminder(context)
        fun permission(action: String) {
            instrumentation.uiAutomation.executeShellCommand(
                "pm $action ${base.packageName} android.permission.POST_NOTIFICATIONS").close()
            Thread.sleep(300)
        }
        try {
            reminder.setUser("test-user")
            reminder.save("test-user", true, false, "Asia/Tokyo")
            reminder.deliver()
            assertNull(prefs.getString("test-user-last", null))
            permission("grant")
            reminder.deliver()
            val delivered = prefs.getString("test-user-last", null)
            assertNotNull(delivered)
            assertEquals(1, manager.activeNotifications.count { it.id == 10 })
            reminder.deliver()
            assertEquals(delivered, prefs.getString("test-user-last", null))
            assertEquals(1, manager.activeNotifications.count { it.id == 10 })
            reminder.save("test-user", false, false, "Asia/Tokyo")
            assertEquals(0, manager.activeNotifications.count { it.id == 10 })
            reminder.save("test-user", true, true, "Asia/Tokyo")
            reminder.setUser(null)
            assertNull(prefs.getString("active", null))
            reminder.deliver()
            assertEquals(0, manager.activeNotifications.count { it.id == 10 })
        } finally {
            reminder.save("test-user", false, false, "Asia/Tokyo")
            prefs.edit().clear().commit()
        }
    }
}
