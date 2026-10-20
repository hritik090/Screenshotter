package io.github.hritik090.screenshotter.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal .docx (Office Open XML) writer: a title, then for each section a heading,
 * the note text and an inline picture. Opens in Word, Google Docs and LibreOffice.
 * Pure JVM so it can be unit tested without a device.
 */
class DocxWriter {

    class Image(val bytes: ByteArray, val widthPx: Int, val heightPx: Int, val extension: String = "jpeg")

    class Section(val heading: String, val note: String, val image: Image?)

    fun write(title: String, subtitle: String, sections: List<Section>, out: OutputStream) {
        val zip = ZipOutputStream(out)
        val images = sections.mapNotNull { it.image }

        zip.put("[Content_Types].xml", contentTypes(images))
        zip.put("_rels/.rels", ROOT_RELS)
        zip.put("word/_rels/document.xml.rels", documentRels(images))
        zip.put("word/document.xml", documentXml(title, subtitle, sections))
        images.forEachIndexed { i, image ->
            zip.putNextEntry(ZipEntry("word/media/image${i + 1}.${image.extension}"))
            zip.write(image.bytes)
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun contentTypes(images: List<Image>): String {
        val imageDefaults = images.map { it.extension }.distinct().joinToString("") { ext ->
            val mime = if (ext == "png") "image/png" else "image/jpeg"
            """<Default Extension="$ext" ContentType="$mime"/>"""
        }
        return XML_HEADER +
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            imageDefaults +
            """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
            "</Types>"
    }

    private fun documentRels(images: List<Image>): String {
        val rels = images.mapIndexed { i, image ->
            """<Relationship Id="${imageRelId(i)}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image${i + 1}.${image.extension}"/>"""
        }.joinToString("")
        return XML_HEADER +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$rels</Relationships>"""
    }

    private fun documentXml(title: String, subtitle: String, sections: List<Section>): String {
        val body = StringBuilder()
        body.append(paragraph(title, bold = true, size = 40))
        body.append(paragraph(subtitle, size = 20, color = "666666"))

        var imageIndex = 0
        sections.forEachIndexed { i, section ->
            if (i > 0) body.append("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")
            body.append(paragraph(section.heading, bold = true, size = 28))
            if (section.note.isBlank()) {
                body.append(paragraph("(no note)", size = 22, color = "999999", italic = true))
            } else {
                section.note.lines().forEach { body.append(paragraph(it, size = 22)) }
            }
            section.image?.let {
                body.append(picture(it, imageIndex))
                imageIndex++
            }
        }

        return XML_HEADER +
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" """ +
            """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
            """xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" """ +
            """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """ +
            """xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">""" +
            "<w:body>$body" +
            """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>""" +
            """<w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>""" +
            "</w:body></w:document>"
    }

    private fun paragraph(
        text: String,
        bold: Boolean = false,
        italic: Boolean = false,
        size: Int = 22,
        color: String? = null,
    ): String {
        val props = buildString {
            if (bold) append("<w:b/>")
            if (italic) append("<w:i/>")
            color?.let { append("""<w:color w:val="$it"/>""") }
            append("""<w:sz w:val="$size"/>""")
        }
        return """<w:p><w:pPr><w:spacing w:after="120"/></w:pPr><w:r><w:rPr>$props</w:rPr>""" +
            """<w:t xml:space="preserve">${escape(text)}</w:t></w:r></w:p>"""
    }

    private fun picture(image: Image, index: Int): String {
        val scale = minOf(MAX_WIDTH_EMU.toDouble() / image.widthPx, MAX_HEIGHT_EMU.toDouble() / image.heightPx)
        val cx = (image.widthPx * scale).toLong()
        val cy = (image.heightPx * scale).toLong()
        val id = index + 1
        return """<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:drawing>""" +
            """<wp:inline distT="0" distB="0" distL="0" distR="0">""" +
            """<wp:extent cx="$cx" cy="$cy"/>""" +
            """<wp:docPr id="$id" name="Screenshot $id"/>""" +
            """<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect="1"/></wp:cNvGraphicFramePr>""" +
            """<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">""" +
            """<pic:pic><pic:nvPicPr><pic:cNvPr id="$id" name="image$id.${image.extension}"/><pic:cNvPicPr/></pic:nvPicPr>""" +
            """<pic:blipFill><a:blip r:embed="${imageRelId(index)}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>""" +
            """<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>""" +
            """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>""" +
            "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
    }

    private fun imageRelId(index: Int) = "rIdImg${index + 1}"

    companion object {
        // 914400 EMU per inch: 6.5in x 7.5in fits an A4 page with margins and a heading.
        private const val MAX_WIDTH_EMU = 5_943_600L
        private const val MAX_HEIGHT_EMU = 6_858_000L
        private const val XML_HEADER = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
        private const val ROOT_RELS = XML_HEADER +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
            "</Relationships>"

        fun escape(text: String): String = buildString(text.length) {
            for (c in text) {
                when {
                    c == '&' -> append("&amp;")
                    c == '<' -> append("&lt;")
                    c == '>' -> append("&gt;")
                    c == '"' -> append("&quot;")
                    c == '\t' -> append(' ')
                    // Control characters are not allowed in XML 1.0.
                    c < ' ' -> {}
                    else -> append(c)
                }
            }
        }
    }
}
