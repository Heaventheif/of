package org.openfilemanager.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** مساعد موحّد لإنشاء قنوات الإشعارات. */
object NotificationChannels {

    /**
     * ينشئ قناة بالخصائص القياسية (صامتة، بلا اهتزاز، بلا شارة). إن وُجدت مسبقاً
     * يحدّثها (Android يتجاهل التغيير في الأهمية بعد الإنشاء لكن يقبل باقي الخصائص).
     *
     * @param oldChannelIds معرفات قنوات قديمة تُحذف (ترحيل).
     */
    fun ensure(
        context: Context,
        channelId: String,
        name: String,
        description: String,
        importance: Int,
        vararg oldChannelIds: String
    ) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        oldChannelIds.forEach { nm.deleteNotificationChannel(it) }
        val channel = NotificationChannel(channelId, name, importance).apply {
            this.description = description
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }
}
