package li.mofanx.epso.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationManagerCompat
import li.mofanx.epso.META
import li.mofanx.epso.app

sealed class NotifChannel(
    val id: String,
    val name: String? = null,
    val desc: String? = null,
) {
    data object Default : NotifChannel(
        id = "0",
    )
}

fun initChannel() {
    val channels = arrayOf(NotifChannel.Default)
    val manager = NotificationManagerCompat.from(app)
    // delete old channels
    manager.notificationChannels.filter { channels.none { c -> c.id == it.id } }.forEach {
        manager.deleteNotificationChannel(it.id)
    }
    // create/update new channels
    channels.forEach {
        val channel = NotificationChannel(
            it.id,
            it.name ?: META.appName,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = it.desc
        }
        manager.createNotificationChannel(channel)
    }
}
