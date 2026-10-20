package io.github.hritik090.screenshotter.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** One screenshot in the document, with the context the user typed for it. */
data class NoteEntry(
    val id: String,
    val createdAt: Long,
    val imageFileName: String,
    val note: String,
    /** MediaStore id of the original screenshot, used to avoid saving the same one twice. */
    val sourceId: Long,
)

/**
 * The single document: an ordered list of screenshots + notes.
 * Screenshots are copied into the app's private storage so the document keeps working
 * even if the originals are deleted from the gallery.
 */
object NotesRepository {
    private const val DOC_FILE = "document.json"
    private const val IMAGES_DIR = "screenshots"

    private val lock = Any()
    private lateinit var appContext: Context
    private val _entries = MutableStateFlow<List<NoteEntry>>(emptyList())
    val entries: StateFlow<List<NoteEntry>> = _entries

    fun init(context: Context) {
        synchronized(lock) {
            if (::appContext.isInitialized) return
            appContext = context.applicationContext
            _entries.value = load()
        }
    }

    fun imageFile(entry: NoteEntry): File = File(imagesDir(), entry.imageFileName)

    /** Copies the screenshot into the document and attaches [note]. Safe to call twice for the same screenshot. */
    suspend fun addScreenshot(source: Uri, sourceId: Long, note: String): NoteEntry = withContext(Dispatchers.IO) {
        findBySource(sourceId)?.let { existing ->
            if (note.isNotBlank() && note != existing.note) {
                val merged = if (existing.note.isBlank()) note else existing.note + "\n" + note
                updateNote(existing.id, merged)
            }
            return@withContext existing
        }

        val extension = when (appContext.contentResolver.getType(source)) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            else -> "png"
        }
        val id = UUID.randomUUID().toString()
        val target = File(imagesDir(), "$id.$extension")
        val input = appContext.contentResolver.openInputStream(source)
            ?: throw IOException("Could not open screenshot $source")
        input.use { inp -> target.outputStream().use { out -> inp.copyTo(out) } }

        val entry = NoteEntry(id, System.currentTimeMillis(), target.name, note.trim(), sourceId)
        synchronized(lock) {
            // Another caller may have saved it while we were copying.
            findBySource(sourceId)?.let {
                target.delete()
                return@withContext it
            }
            _entries.value = _entries.value + entry
            save()
        }
        entry
    }

    fun updateNote(id: String, note: String) = synchronized(lock) {
        _entries.value = _entries.value.map { if (it.id == id) it.copy(note = note.trim()) else it }
        save()
    }

    fun delete(id: String) = synchronized(lock) {
        _entries.value.firstOrNull { it.id == id }?.let { imageFile(it).delete() }
        _entries.value = _entries.value.filterNot { it.id == id }
        save()
    }

    fun clear() = synchronized(lock) {
        imagesDir().listFiles()?.forEach { it.delete() }
        _entries.value = emptyList()
        save()
    }

    private fun findBySource(sourceId: Long): NoteEntry? =
        if (sourceId < 0) null else _entries.value.firstOrNull { it.sourceId == sourceId }

    private fun imagesDir(): File = File(appContext.filesDir, IMAGES_DIR).apply { mkdirs() }

    private fun docFile(): File = File(appContext.filesDir, DOC_FILE)

    private fun load(): List<NoteEntry> {
        val file = docFile()
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                NoteEntry(
                    id = o.getString("id"),
                    createdAt = o.getLong("createdAt"),
                    imageFileName = o.getString("image"),
                    note = o.optString("note"),
                    sourceId = o.optLong("sourceId", -1),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save() {
        val array = JSONArray()
        _entries.value.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("createdAt", it.createdAt)
                    .put("image", it.imageFileName)
                    .put("note", it.note)
                    .put("sourceId", it.sourceId)
            )
        }
        // Write-then-rename so a crash mid-write never corrupts the document.
        val tmp = File(appContext.filesDir, "$DOC_FILE.tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(docFile())
    }
}
