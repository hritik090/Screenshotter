package io.github.hritik090.screenshotter.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import io.github.hritik090.screenshotter.data.NoteEntry
import io.github.hritik090.screenshotter.data.NotesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat(val mimeType: String, val extension: String, val label: String) {
    PDF("application/pdf", "pdf", "PDF"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx", "Word"),
}

object Exporter {
    private const val TITLE = "Screenshot Notes"

    fun suggestedFileName(format: ExportFormat): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "Screenshot Notes $date.${format.extension}"
    }

    /** Writes the document to a location the user picked (Storage Access Framework). */
    suspend fun exportTo(context: Context, format: ExportFormat, target: Uri) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(target)
            ?: error("Could not open $target for writing")
        out.use { write(format, NotesRepository.entries.value, it) }
    }

    /** Writes the document to the cache and returns a share intent for it. */
    suspend fun shareIntent(context: Context, format: ExportFormat): Intent = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, suggestedFileName(format))
        file.outputStream().use { write(format, NotesRepository.entries.value, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(format.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, TITLE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Intent.createChooser(send, "Share ${format.label} document")
    }

    private fun write(format: ExportFormat, entries: List<NoteEntry>, out: OutputStream) {
        when (format) {
            ExportFormat.PDF -> writePdf(entries, out)
            ExportFormat.DOCX -> writeDocx(entries, out)
        }
    }

    private fun subtitle(entries: List<NoteEntry>): String {
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date())
        val count = if (entries.size == 1) "1 screenshot" else "${entries.size} screenshots"
        return "Exported $date · $count"
    }

    private fun heading(index: Int, entry: NoteEntry): String {
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.createdAt))
        return "#${index + 1} · $date"
    }

    // ---------------------------------------------------------------- Word

    private fun writeDocx(entries: List<NoteEntry>, out: OutputStream) {
        val sections = entries.mapIndexed { i, entry ->
            val image = decodeScaled(NotesRepository.imageFile(entry), 1400)?.let { bitmap ->
                val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                DocxWriter.Image(bytes, bitmap.width, bitmap.height).also { bitmap.recycle() }
            }
            DocxWriter.Section(heading(i, entry), entry.note, image)
        }
        DocxWriter().write(TITLE, subtitle(entries), sections, out)
    }

    // ---------------------------------------------------------------- PDF

    private const val PAGE_W = 595 // A4 in points
    private const val PAGE_H = 842
    private const val MARGIN = 40
    private const val CONTENT_W = PAGE_W - 2 * MARGIN

    private fun writePdf(entries: List<NoteEntry>, out: OutputStream) {
        val doc = PdfDocument()
        val titlePaint = textPaint(22f, bold = true)
        val subtitlePaint = textPaint(11f, color = Color.GRAY)
        val headingPaint = textPaint(15f, bold = true)
        val notePaint = textPaint(12f)
        val emptyNotePaint = textPaint(12f, color = Color.GRAY).apply { textSkewX = -0.2f }
        val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val borderPaint = Paint().apply { style = Paint.Style.STROKE; color = Color.LTGRAY; strokeWidth = 0.5f }

        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f

        fun newPage() {
            page?.let(doc::finishPage)
            pageNumber++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create()).also {
                canvas = it.canvas
            }
            y = MARGIN.toFloat()
        }

        /** Draws [text] wrapped to the content width, continuing onto new pages as needed. */
        fun drawText(text: String, paint: TextPaint, spacingAfter: Float) {
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, CONTENT_W)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1f)
                .build()
            for (line in 0 until layout.lineCount) {
                val top = layout.getLineTop(line)
                val bottom = layout.getLineBottom(line)
                val height = bottom - top
                if (y + height > PAGE_H - MARGIN) newPage()
                val c = canvas!!
                c.save()
                c.translate(MARGIN.toFloat(), y - top)
                c.clipRect(0, top, CONTENT_W, bottom)
                layout.draw(c)
                c.restore()
                y += height
            }
            y += spacingAfter
        }

        newPage()
        drawText(TITLE, titlePaint, 4f)
        drawText(subtitle(entries), subtitlePaint, 24f)

        entries.forEachIndexed { i, entry ->
            if (i > 0) newPage()
            drawText(heading(i, entry), headingPaint, 8f)
            if (entry.note.isBlank()) drawText("(no note)", emptyNotePaint, 12f)
            else drawText(entry.note, notePaint, 12f)

            val bitmap = decodeScaled(NotesRepository.imageFile(entry), 1200) ?: return@forEachIndexed
            if (PAGE_H - MARGIN - y < 250) newPage()
            val available = PAGE_H - MARGIN - y
            val scale = minOf(CONTENT_W.toFloat() / bitmap.width, available / bitmap.height)
            val w = (bitmap.width * scale).toInt()
            val h = (bitmap.height * scale).toInt()
            val left = MARGIN + (CONTENT_W - w) / 2
            val dst = Rect(left, y.toInt(), left + w, y.toInt() + h)
            canvas!!.drawBitmap(bitmap, null, dst, imagePaint)
            canvas!!.drawRect(dst, borderPaint)
            bitmap.recycle()
            y += h
        }

        page?.let(doc::finishPage)
        doc.writeTo(out)
        doc.close()
    }

    private fun textPaint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    /** Decodes [file] so that its longest side is at most [maxSide] pixels. */
    private fun decodeScaled(file: File, maxSide: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null

        val longest = maxOf(decoded.width, decoded.height)
        if (longest <= maxSide) return decoded
        val ratio = maxSide.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            decoded, (decoded.width * ratio).toInt().coerceAtLeast(1), (decoded.height * ratio).toInt().coerceAtLeast(1), true,
        )
        if (scaled != decoded) decoded.recycle()
        return scaled
    }
}
