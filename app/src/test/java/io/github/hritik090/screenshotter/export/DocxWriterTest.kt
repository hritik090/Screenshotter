package io.github.hritik090.screenshotter.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class DocxWriterTest {

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                files[entry.name] = zip.readBytes()
            }
        }
        return files
    }

    private fun assertWellFormed(xml: ByteArray) {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(xml))
    }

    @Test
    fun writesValidPackageWithImagesAndEscapedNotes() {
        val image = DocxWriter.Image(byteArrayOf(1, 2, 3), widthPx = 1080, heightPx = 2400)
        val sections = listOf(
            DocxWriter.Section("#1", "Bug <here> & \"there\"\nSecond line\u0007", image),
            DocxWriter.Section("#2", "", null),
            DocxWriter.Section("#3", "Third", image),
        )
        val out = ByteArrayOutputStream()
        DocxWriter().write("Screenshot Notes", "Exported today", sections, out)

        val files = unzip(out.toByteArray())
        assertEquals(
            setOf(
                "[Content_Types].xml", "_rels/.rels", "word/_rels/document.xml.rels", "word/document.xml",
                "word/media/image1.jpeg", "word/media/image2.jpeg",
            ),
            files.keys,
        )
        files.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.values.forEach(::assertWellFormed)
        assertArrayEquals(byteArrayOf(1, 2, 3), files["word/media/image1.jpeg"])

        val document = String(files.getValue("word/document.xml"))
        assertTrue(document.contains("Bug &lt;here&gt; &amp; &quot;there&quot;"))
        assertTrue(document.contains(">Second line<"))
        assertTrue(document.contains("(no note)"))
        assertTrue(document.contains("""r:embed="rIdImg1""""))
        assertTrue(document.contains("""r:embed="rIdImg2""""))

        val rels = String(files.getValue("word/_rels/document.xml.rels"))
        assertTrue(rels.contains("""Id="rIdImg2""""))
        assertTrue(rels.contains("""Target="media/image2.jpeg""""))
    }

    @Test
    fun tallScreenshotsAreScaledToFitThePage() {
        val image = DocxWriter.Image(byteArrayOf(0), widthPx = 1000, heightPx = 4000)
        val out = ByteArrayOutputStream()
        DocxWriter().write("t", "s", listOf(DocxWriter.Section("h", "n", image)), out)
        val document = String(unzip(out.toByteArray()).getValue("word/document.xml"))
        // Height-limited: 7.5in tall, 1.875in wide.
        assertTrue(document.contains("""<wp:extent cx="1714500" cy="6858000"/>"""))
    }
}
