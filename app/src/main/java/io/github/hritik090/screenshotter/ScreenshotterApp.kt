package io.github.hritik090.screenshotter

import android.app.Application
import io.github.hritik090.screenshotter.data.NotesRepository
import io.github.hritik090.screenshotter.detect.Notifications

class ScreenshotterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotesRepository.init(this)
        Notifications.createChannels(this)
    }
}
