package com.ryan.scanme.core.data.export

import java.io.OutputStream
import java.util.Locale

/** One PDF page showing one JPEG image edge to edge. Sizes in PDF points (1/72 inch). */
class PdfImagePage(
    val jpeg: ByteArray,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val widthPt: Double,
    val heightPt: Double,
)

/**
 * Minimal PDF 1.4 writer for image-only pages, embedding JPEGs as-is (DCTDecode).
 *
 * Why not android.graphics.pdf.PdfDocument: it re-encodes bitmaps itself, giving no control over image
 * compression, and scanned pages typically come out several times larger. Passing our own JPEG keeps a
 * page around 200-500 KB, which matters when sharing over WhatsApp.
 */
object JpegPdfWriter {

    fun write(pages: List<PdfImagePage>, out: OutputStream) {
        require(pages.isNotEmpty()) { "PDF needs at least one page" }
        val w = CountingOutput(out)
        val offsets = mutableListOf<Long>()
        fun beginObject(number: Int) {
            check(offsets.size == number - 1) { "Objects must be written in order" }
            offsets += w.count
            w.ascii("$number 0 obj\n")
        }

        w.ascii("%PDF-1.4\n")
        w.bytes(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

        // Object layout: 1 catalog, 2 page tree, then per page i: page (3+3i), content (4+3i), image (5+3i).
        beginObject(1)
        w.ascii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        beginObject(2)
        val kids = pages.indices.joinToString(" ") { "${3 + 3 * it} 0 R" }
        w.ascii("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>\nendobj\n")

        pages.forEachIndexed { i, page ->
            val pageObj = 3 + 3 * i
            val width = num(page.widthPt)
            val height = num(page.heightPt)
            beginObject(pageObj)
            w.ascii(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] " +
                    "/Resources << /XObject << /Im0 ${pageObj + 2} 0 R >> >> /Contents ${pageObj + 1} 0 R >>\nendobj\n",
            )
            val content = "q $width 0 0 $height 0 0 cm /Im0 Do Q\n".toByteArray(Charsets.US_ASCII)
            beginObject(pageObj + 1)
            w.ascii("<< /Length ${content.size} >>\nstream\n")
            w.bytes(content)
            w.ascii("endstream\nendobj\n")
            beginObject(pageObj + 2)
            w.ascii(
                "<< /Type /XObject /Subtype /Image /Width ${page.pixelWidth} /Height ${page.pixelHeight} " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${page.jpeg.size} >>\nstream\n",
            )
            w.bytes(page.jpeg)
            w.ascii("\nendstream\nendobj\n")
        }

        val xref = w.count
        w.ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        // Each entry is exactly 20 bytes, as the spec requires.
        offsets.forEach { w.ascii(String.format(Locale.US, "%010d 00000 n \n", it)) }
        w.ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        w.flush()
    }

    private fun num(v: Double) = String.format(Locale.US, "%.2f", v)

    private class CountingOutput(private val out: OutputStream) {
        var count = 0L
            private set

        fun ascii(s: String) = bytes(s.toByteArray(Charsets.US_ASCII))

        fun bytes(b: ByteArray) {
            out.write(b)
            count += b.size
        }

        fun flush() = out.flush()
    }
}
