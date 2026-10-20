package io.github.hritik090.screenshotter.detect

import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.hritik090.screenshotter.Permissions
import io.github.hritik090.screenshotter.ui.NoteActivity

/**
 * Foreground service that watches MediaStore for newly added images that live in a
 * "Screenshots" folder, and pops up the note bar for each one.
 */
class ScreenshotWatcherService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var observer: ContentObserver
    private val handledIds = LinkedHashSet<Long>()
    private var startedAtSeconds = 0L
    private val scanRunnable = Runnable { scanForNewScreenshots() }

    override fun onCreate() {
        super.onCreate()
        startedAtSeconds = System.currentTimeMillis() / 1000
        thread = HandlerThread("screenshot-watcher").apply { start() }
        handler = Handler(thread.looper)
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = scheduleScan()
            override fun onChange(selfChange: Boolean, uri: Uri?) = scheduleScan()
        }
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, Notifications.WATCHER_ID, Notifications.watcherNotification(this), type)
        return START_STICKY
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** MediaStore fires several change events per screenshot; coalesce them. */
    private fun scheduleScan() {
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, 500)
    }

    private fun scanForNewScreenshots() {
        if (!Permissions.hasImages(this)) return
        val nowSeconds = System.currentTimeMillis() / 1000
        val since = maxOf(startedAtSeconds, nowSeconds - 60)

        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Images.Media.DATA)
            }
        }.toTypedArray()

        val found = mutableListOf<Long>()
        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Images.Media.DATE_ADDED} >= ?",
                arrayOf(since.toString()),
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                var rows = 0
                while (cursor.moveToNext() && rows++ < 10) {
                    val id = cursor.getLong(0)
                    if (id in handledIds) continue
                    val looksLikeScreenshot = (1 until cursor.columnCount).any { col ->
                        cursor.getString(col)?.contains("screenshot", ignoreCase = true) == true
                    }
                    if (looksLikeScreenshot) found += id
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Lost access to images", e)
            return
        }

        // Oldest first so stacked popups end with the newest on top.
        found.asReversed().forEach { id ->
            handledIds += id
            if (handledIds.size > 200) handledIds.remove(handledIds.first())
            onScreenshot(id)
        }
    }

    private fun onScreenshot(id: Long) {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        Log.i(TAG, "Screenshot detected: $uri")
        // The notification is a fallback: the popup cancels it as soon as it opens.
        Notifications.showScreenshotCaptured(this, id, uri)
        if (Permissions.hasOverlay(this)) {
            try {
                startActivity(NoteActivity.intent(this, id, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.w(TAG, "Could not open note popup", e)
            }
        }
    }

    companion object {
        private const val TAG = "ScreenshotWatcher"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ScreenshotWatcherService::class.java))
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException when started from the background.
                Log.w(TAG, "Could not start watcher", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenshotWatcherService::class.java))
        }
    }
}
