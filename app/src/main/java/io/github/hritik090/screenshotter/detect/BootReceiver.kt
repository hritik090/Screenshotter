package io.github.hritik090.screenshotter.detect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.hritik090.screenshotter.Permissions
import io.github.hritik090.screenshotter.Prefs

/** Restarts the watcher after a reboot or app update if the user had it switched on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs.watchEnabled(context) && Permissions.hasImages(context)) {
            ScreenshotWatcherService.start(context)
        }
    }
}
