package io.github.hritik090.screenshotter.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.hritik090.screenshotter.Permissions
import io.github.hritik090.screenshotter.Prefs
import io.github.hritik090.screenshotter.data.NoteEntry
import io.github.hritik090.screenshotter.data.NotesRepository
import io.github.hritik090.screenshotter.detect.ScreenshotWatcherService
import io.github.hritik090.screenshotter.export.ExportFormat
import io.github.hritik090.screenshotter.export.Exporter
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    /** Bumped on every resume so permission state is re-read after visiting system settings. */
    private val resumeCount = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme { MainScreen(resumeCount.intValue) }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount.intValue++
    }
}

private data class SetupState(
    val images: Boolean,
    val notifications: Boolean,
    val overlay: Boolean,
    val battery: Boolean,
)

private fun readSetup(context: Context) = SetupState(
    images = Permissions.hasImages(context),
    notifications = Permissions.hasNotifications(context),
    overlay = Permissions.hasOverlay(context),
    battery = Permissions.ignoresBatteryOptimizations(context),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(resumeCount: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val entries by NotesRepository.entries.collectAsState()

    var permissionTick by remember { mutableIntStateOf(0) }
    val setup = remember(resumeCount, permissionTick) { readSetup(context) }
    var watching by remember { mutableStateOf(Prefs.watchEnabled(context)) }

    val requestImages = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionTick++
        if (granted) {
            watching = true
            Prefs.setWatchEnabled(context, true)
        } else {
            scope.launch { snackbar.showSnackbar("Photo access is needed to see new screenshots. Choose \"Allow all\".") }
        }
    }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionTick++
    }

    LaunchedEffect(watching, setup.images) {
        if (watching && setup.images) ScreenshotWatcherService.start(context)
        else if (!watching) ScreenshotWatcherService.stop(context)
    }

    fun runExport(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                snackbar.showSnackbar("Export failed: ${e.message}")
            }
        }
    }

    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.PDF.mimeType)) { uri ->
        uri?.let { runExport { Exporter.exportTo(context, ExportFormat.PDF, it); snackbar.showSnackbar("PDF saved") } }
    }
    val saveDocx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.DOCX.mimeType)) { uri ->
        uri?.let { runExport { Exporter.exportTo(context, ExportFormat.DOCX, it); snackbar.showSnackbar("Word document saved") } }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var exportMenuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Screenshot Notes") },
                actions = {
                    Box {
                        IconButton(onClick = { exportMenuOpen = true }, enabled = entries.isNotEmpty()) {
                            Icon(Icons.Default.Share, contentDescription = "Export")
                        }
                        DropdownMenu(expanded = exportMenuOpen, onDismissRequest = { exportMenuOpen = false }) {
                            DropdownMenuItem(text = { Text("Save as PDF") }, onClick = {
                                exportMenuOpen = false
                                savePdf.launch(Exporter.suggestedFileName(ExportFormat.PDF))
                            })
                            DropdownMenuItem(text = { Text("Save as Word (.docx)") }, onClick = {
                                exportMenuOpen = false
                                saveDocx.launch(Exporter.suggestedFileName(ExportFormat.DOCX))
                            })
                            HorizontalDivider()
                            ExportFormat.entries.forEach { format ->
                                DropdownMenuItem(text = { Text("Share as ${format.label}") }, onClick = {
                                    exportMenuOpen = false
                                    runExport { context.startActivity(Exporter.shareIntent(context, format)) }
                                })
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Clear document") },
                                enabled = entries.isNotEmpty(),
                                onClick = { menuOpen = false; confirmClear = true },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SetupCard(
                    setup = setup,
                    watching = watching,
                    onToggleWatching = { on ->
                        if (on && !setup.images) {
                            requestImages.launch(Permissions.imagePermission)
                        } else {
                            watching = on
                            Prefs.setWatchEnabled(context, on)
                        }
                    },
                    onRequestImages = { requestImages.launch(Permissions.imagePermission) },
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onRequestOverlay = {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        )
                    },
                    onRequestBattery = { requestIgnoreBatteryOptimizations(context) },
                    onOpenAppSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        )
                    },
                )
            }

            item {
                Text(
                    text = if (entries.isEmpty()) "Your document" else "Your document · ${entries.size} screenshots",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (entries.isEmpty()) {
                item {
                    Text(
                        "Nothing here yet. Turn on screenshot detection above, then take a screenshot anywhere. " +
                            "A note bar will pop up so you can add context, and it'll show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            itemsIndexed(entries, key = { _, e -> e.id }) { index, entry ->
                EntryCard(index, entry)
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear document?") },
            text = { Text("This deletes all ${entries.size} screenshots and notes from the app. Export first if you need them.") },
            confirmButton = {
                TextButton(onClick = { NotesRepository.clear(); confirmClear = false }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@SuppressLint("BatteryLife")
private fun requestIgnoreBatteryOptimizations(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    try {
        context.startActivity(direct)
    } catch (e: Exception) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

@Composable
private fun SetupCard(
    setup: SetupState,
    watching: Boolean,
    onToggleWatching: (Boolean) -> Unit,
    onRequestImages: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestBattery: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Detect screenshots", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            watching && setup.images -> "On. Take a screenshot to add a note."
                            watching -> "Waiting for photo access"
                            else -> "Off"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = watching && setup.images, onCheckedChange = onToggleWatching)
            }

            val pending = listOf(setup.images, setup.notifications, setup.overlay, setup.battery).count { !it }
            if (pending > 0) {
                Spacer(Modifier.padding(top = 8.dp))
                HorizontalDivider()
                SetupStep(
                    done = setup.images,
                    title = "Photo access",
                    detail = "Required to notice new screenshots. Pick \"Allow all\".",
                    action = "Allow",
                    onClick = onRequestImages,
                    secondaryAction = "Settings" to onOpenAppSettings,
                )
                SetupStep(
                    done = setup.overlay,
                    title = "Pop up over other apps",
                    detail = "Lets the note bar appear right where you took the screenshot. " +
                        "Without it you'll get a notification instead.",
                    action = "Allow",
                    onClick = onRequestOverlay,
                )
                SetupStep(
                    done = setup.notifications,
                    title = "Notifications",
                    detail = "Needed for the background watcher and the fallback reminder.",
                    action = "Allow",
                    onClick = onRequestNotifications,
                    secondaryAction = "Settings" to onOpenAppSettings,
                )
                SetupStep(
                    done = setup.battery,
                    title = "Keep running in background",
                    detail = "Stops the phone from killing the watcher to save battery.",
                    action = "Allow",
                    onClick = onRequestBattery,
                )
            }
        }
    }
}

@Composable
private fun SetupStep(
    done: Boolean,
    title: String,
    detail: String,
    action: String,
    onClick: () -> Unit,
    secondaryAction: Pair<String, () -> Unit>? = null,
) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!done) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(8.dp))
        if (done) {
            Icon(Icons.Default.CheckCircle, contentDescription = "Done", tint = MaterialTheme.colorScheme.primary)
        } else {
            Column(horizontalAlignment = Alignment.End) {
                FilledTonalButton(onClick = onClick) { Text(action) }
                secondaryAction?.let { (label, handler) -> TextButton(onClick = handler) { Text(label) } }
            }
        }
    }
}

@Composable
private fun EntryCard(index: Int, entry: NoteEntry) {
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val date = remember(entry.createdAt) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.createdAt))
    }

    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("#${index + 1} · $date", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, contentDescription = "Edit note") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
            }
            Text(
                text = entry.note.ifBlank { "No note. Tap to add one." },
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = if (entry.note.isBlank()) FontStyle.Italic else FontStyle.Normal,
                color = if (entry.note.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editing = true }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
            AsyncImage(
                model = NotesRepository.imageFile(entry),
                contentDescription = "Screenshot ${index + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .padding(16.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }

    if (editing) {
        var text by remember { mutableStateOf(entry.note) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Edit note") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = { NotesRepository.updateNote(entry.id, text); editing = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this screenshot?") },
            text = { Text("It will be removed from your document. The original stays in your gallery.") },
            confirmButton = {
                TextButton(onClick = { NotesRepository.delete(entry.id); confirmDelete = false }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
