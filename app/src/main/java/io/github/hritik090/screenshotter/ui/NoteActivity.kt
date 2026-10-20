package io.github.hritik090.screenshotter.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import io.github.hritik090.screenshotter.data.NotesRepository
import io.github.hritik090.screenshotter.detect.Notifications
import kotlinx.coroutines.launch

/** The little note bar that pops up over whatever app you were in right after a screenshot. */
class NoteActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        if (uri == null) {
            finish()
            return
        }
        val sourceId = intent.getLongExtra(EXTRA_SOURCE_ID, -1)
        Notifications.cancelScreenshot(this, sourceId)

        setContent {
            AppTheme {
                var saving by remember { mutableStateOf(false) }
                NotePopup(
                    screenshot = uri,
                    saving = saving,
                    onDiscard = { finish() },
                    onSave = { note ->
                        saving = true
                        lifecycleScope.launch {
                            try {
                                NotesRepository.addScreenshot(uri, sourceId, note)
                                Toast.makeText(applicationContext, "Saved to your document", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(applicationContext, "Couldn't save screenshot: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                            finish()
                        }
                    },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_URI = "uri"
        private const val EXTRA_SOURCE_ID = "source_id"

        fun intent(context: Context, sourceId: Long, uri: Uri): Intent =
            Intent(context, NoteActivity::class.java)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_SOURCE_ID, sourceId)
                // Each screenshot gets its own popup, even if one is already open.
                .setData(uri)
    }
}

@Composable
private fun NotePopup(
    screenshot: Uri,
    saving: Boolean,
    onDiscard: () -> Unit,
    onSave: (String) -> Unit,
) {
    var note by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(onBack = onDiscard)

    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = screenshot,
                        contentDescription = "Screenshot you just took",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 54.dp, height = 96.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Screenshot captured", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Add some context. It's saved with the screenshot in your document.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                    placeholder = { Text("What's this screenshot about?") },
                    minLines = 2,
                    maxLines = 6,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDiscard, enabled = !saving) { Text("Discard") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSave(note) }, enabled = !saving) {
                        if (saving) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(if (note.isBlank()) "Save without note" else "Save")
                        }
                    }
                }
            }
        }
    }
}
