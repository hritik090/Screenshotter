package io.github.hritik090.screenshotter.detect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.RemoteInput
import io.github.hritik090.screenshotter.data.NotesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles "Add note" (inline reply) and "Save without note" from the screenshot notification. */
class NoteReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse) ?: return
        val sourceId = intent.getLongExtra(EXTRA_SOURCE_ID, -1)
        val note = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifications.KEY_NOTE)?.toString().orEmpty()

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NotesRepository.addScreenshot(uri, sourceId, note)
            } catch (e: Exception) {
                Log.w("NoteReplyReceiver", "Could not save screenshot", e)
            } finally {
                Notifications.cancelScreenshot(context, sourceId)
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "io.github.hritik090.screenshotter.REPLY"
        const val ACTION_SAVE = "io.github.hritik090.screenshotter.SAVE"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_SOURCE_ID = "source_id"

        fun intent(context: Context, sourceId: Long, uri: Uri, action: String) =
            Intent(context, NoteReplyReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_SOURCE_ID, sourceId)
    }
}
