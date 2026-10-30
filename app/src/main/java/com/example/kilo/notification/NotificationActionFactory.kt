package com.example.kilo.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** Creates package-scoped playback actions for the existing MediaLibraryService. */
object NotificationActionFactory {
    fun pendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun action(context: Context, icon: Int, title: String, action: String, requestCode: Int): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            icon,
            title,
            pendingIntent(context, action, requestCode)
        ).build()
}
