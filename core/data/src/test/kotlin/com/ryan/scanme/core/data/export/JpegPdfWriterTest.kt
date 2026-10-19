package com.ryan.scanme.core.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class JpegPdfWriterTest {

    private fun fakeJpeg(size: Int) = ByteArray(size) { (it % 251).toByte() }.also {
        it[0] = 0xFF.toByte()
        it[1] = 0xD8.toByte()
    }

    private fun pdf(pageCount: Int): ByteArray {
        val pages = (1..pageCount).map { PdfImagePage(fakeJpeg(1000 * it), 1654, 2339, 595.28, 841.89) }
        return ByteArrayOutputStream().also { JpegPdfWriter.write(pages, it) }.toByteArray()
    }

    @Test
    fun `xref offsets point at every object`() {
        val bytes = pdf(3)
        val text = String(bytes, Charsets.ISO_8859_1)
        val startXref = text.substringAfterLast("startxref\n").substringBefore("\n").toInt()
        assertTrue(text.startsWith("xref", startXref))

        val entries = text.substring(startXref).lines().drop(3).takeWhile { it.endsWith(" n ") }
        assertEquals(2 + 3 * 3, entries.size)
        entries.forEachIndexed { i, entry ->
            val offset = entry.substring(0, 10).toInt()
            assertTrue("object ${i + 1} not at $offset", text.startsWith("${i + 1} 0 obj", offset))
        }
        assertTrue(text.trimEnd().endsWith("%%EOF"))
    }

    @Test
    fun `page tree lists all pages and embeds jpeg unchanged`() {
        val bytes = pdf(2)
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("/Kids [3 0 R 6 0 R] /Count 2"))
        assertTrue(text.contains("/MediaBox [0 0 595.28 841.89]"))
        assertTrue(text.contains("/Filter /DCTDecode /Length 2000"))
        val jpeg = fakeJpeg(2000)
        val start = text.indexOf("/Length 2000 >>\nstream\n") + "/Length 2000 >>\nstream\n".length
        assertTrue(bytes.copyOfRange(start, start + jpeg.size).contentEquals(jpeg))
    }
}
