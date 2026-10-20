package io.github.hritik090.screenshotter.detect

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import io.github.hritik090.screenshotter.Permissions
import io.github.hritik090.screenshotter.R
import io.github.hritik090.screenshotter.ui.MainActivity
import io.github.hritik090.screenshotter.ui.NoteActivity

object Notifications {
    const val WATCHER_ID = 1
    private const val CHANNEL_WATCHER = "watcher"
    private const val CHANNEL_CAPTURED = "captured"
    const val KEY_NOTE = "note"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_WATCHER, "Screenshot watcher", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the app is listening for screenshots"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CAPTURED, "New screenshots", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Lets you add a note to a screenshot you just took"
            }
        )
    }

    fun watcherNotification(context: Context): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_WATCHER)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle("Watching for screenshots")
            .setContentText("Take a screenshot to add a note to it")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @SuppressLint("MissingPermission")
    fun showScreenshotCaptured(context: Context, sourceId: Long, uri: Uri) {
        if (!Permissions.hasNotifications(context)) return
        val notificationId = notificationId(sourceId)

        val openPopup = PendingIntent.getActivity(
            context, notificationId, NoteActivity.intent(context, sourceId, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // RemoteInput needs a mutable PendingIntent so the system can fill in the typed text.
        val replyIntent = PendingIntent.getBroadcast(
            context, notificationId * 2,
            NoteReplyReceiver.intent(context, sourceId, uri, NoteReplyReceiver.ACTION_REPLY),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val saveIntent = PendingIntent.getBroadcast(
            context, notificationId * 2 + 1,
            NoteReplyReceiver.intent(context, sourceId, uri, NoteReplyReceiver.ACTION_SAVE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val remoteInput = RemoteInput.Builder(KEY_NOTE).setLabel("Context for this screenshot").build()
        val replyAction = NotificationCompat.Action.Builder(R.drawable.ic_stat_note, "Add note", replyIntent)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_CAPTURED)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle("Screenshot captured")
            .setContentText("Add some context to save it to your document")
            .setContentIntent(openPopup)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(replyAction)
            .addAction(R.drawable.ic_stat_note, "Save without note", saveIntent)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    fun cancelScreenshot(context: Context, sourceId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(sourceId))
    }

    private fun notificationId(sourceId: Long): Int = 1000 + (sourceId % 1_000_000).toInt()
}
