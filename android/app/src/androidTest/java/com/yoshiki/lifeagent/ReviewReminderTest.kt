package com.yoshiki.lifeagent

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import com.yoshiki.lifeagent.reminders.ReviewReminder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ReviewReminderTest {
    @Test fun deliveryIsDeduplicatedAndUserSwitchOrLogoutRemovesNotification() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val storage = mutableListOf<SharedPreferences>()
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("test-$name", mode).also { storage += it }
        }
        val manager = base.getSystemService(NotificationManager::class.java)
        val reminder = ReviewReminder(context)
        assumeTrue(base.checkSelfPermission("android.permission.POST_NOTIFICATIONS") ==
            PackageManager.PERMISSION_GRANTED)
        try {
            reminder.setUser("a")
            reminder.save("a", true, false, "Asia/Tokyo")
            reminder.deliver()
            val first = manager.activeNotifications.single().postTime
            reminder.deliver()
            assertEquals(first, manager.activeNotifications.single().postTime)
            reminder.setUser("b")
            assertFalse(reminder.enabled("b"))
            assertTrue(manager.activeNotifications.isEmpty())
            reminder.setUser("a")
            reminder.setUser(null)
            reminder.deliver()
            assertTrue(manager.activeNotifications.isEmpty())
        } finally {
            reminder.setUser(null)
            storage.forEach { it.edit().clear().commit() }
        }
    }
}
