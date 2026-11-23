package com.ryan.scanme.core.data.repository

import com.ryan.scanme.core.data.db.DocumentDao
import com.ryan.scanme.core.data.db.DocumentEntity
import com.ryan.scanme.core.data.db.PageEntity
import com.ryan.scanme.core.domain.model.Page
import com.ryan.scanme.core.imaging.enhance.FilterPreset
import com.ryan.scanme.core.imaging.enhance.FilterSettings
import com.ryan.scanme.core.imaging.geometry.Quad
import com.ryan.scanme.core.imaging.geometry.Vec2
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Save / reload / prune behaviour against an in-memory DAO; the files are real. */
class RoomDocumentRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dao = FakeDao()
    private val root by lazy { tmp.newFolder("documents") }
    private val cache by lazy { tmp.newFolder("cache") }
    private val repo by lazy { RoomDocumentRepository(dao, root, clock = { 1000L }) }

    private fun photo(name: String) = File(cache, name).apply { writeText(name) }

    private fun page(file: File, preset: FilterPreset = FilterPreset.AUTO_COLOR) = Page(
        originalPath = file.path,
        photoRotation = 90,
        quad = Quad(Vec2(0.1, 0.2), Vec2(0.9, 0.15), Vec2(0.95, 0.9), Vec2(0.05, 0.85)),
        rotationDegrees = 180,
        dewarp = false,
        filter = FilterSettings(preset, intensity = 0.7),
        focalLengthPx = 3000.0,
        thumbnailPath = null,
    )

    @Test
    fun `save copies cache photos into document storage and round-trips every field`() = runBlocking {
        val a = photo("a.jpg")
        val id = repo.save(null, "Scan 1", listOf(page(a), page(photo("b.jpg"), FilterPreset.BLACK_WHITE)))

        val doc = repo.document(id)!!
        assertEquals("Scan 1", doc.title)
        assertEquals(2, doc.pages.size)
        val p = doc.pages[0]
        assertTrue(p.originalPath.startsWith(File(root, id.toString()).path))
        assertEquals("a.jpg", File(p.originalPath).readText())
        assertEquals(page(a).copy(originalPath = p.originalPath), p)
        assertEquals(FilterPreset.BLACK_WHITE, doc.pages[1].filter.preset)
        assertTrue("cache file is left to its owner", a.exists())
    }

    @Test
    fun `re-saving keeps stored files, applies order and removes dropped pages`() = runBlocking {
        val id = repo.save(null, "Doc", listOf(page(photo("a.jpg")), page(photo("b.jpg")), page(photo("c.jpg"))))
        val stored = repo.document(id)!!.pages

        // Reorder c, a and drop b, reusing the stored paths (as the editor does after loading).
        repo.save(id, "Doc", listOf(stored[2], stored[0]))

        val pages = repo.document(id)!!.pages
        assertEquals(listOf(stored[2].originalPath, stored[0].originalPath), pages.map { it.originalPath })
        assertFalse("dropped page's photo is deleted", File(stored[1].originalPath).exists())
        assertEquals(2, File(root, id.toString()).listFiles()!!.size)
    }

    @Test
    fun `missing thumbnail is dropped, delete removes rows and files`() = runBlocking {
        val id = repo.save(null, "Doc", listOf(page(photo("a.jpg")).copy(thumbnailPath = File(cache, "gone.jpg").path)))
        assertNull(repo.document(id)!!.pages[0].thumbnailPath)

        repo.delete(id)
        assertNull(repo.document(id))
        assertFalse(File(root, id.toString()).exists())
    }

    @Test
    fun `malformed quad falls back to full frame`() {
        assertEquals(Quad.FULL_FRAME, decodeQuad("1,2,3"))
        val q = Quad(Vec2(0.1, 0.2), Vec2(0.3, 0.4), Vec2(0.5, 0.6), Vec2(0.7, 0.8))
        assertEquals(q, decodeQuad(encodeQuad(q)))
        assertNotEquals(Quad.FULL_FRAME, q)
    }

    private class FakeDao : DocumentDao {
        val docs = MutableStateFlow<List<DocumentEntity>>(emptyList())
        val pageRows = MutableStateFlow<List<PageEntity>>(emptyList())
        private var nextId = 1L

        override fun observeDocuments(): Flow<List<DocumentEntity>> = docs
        override fun observeAllPages(): Flow<List<PageEntity>> = pageRows
        override suspend fun document(id: Long) = docs.value.firstOrNull { it.id == id }
        override suspend fun pages(documentId: Long) = pageRows.value.filter { it.documentId == documentId }.sortedBy { it.orderIndex }
        override suspend fun insertDocument(document: DocumentEntity): Long {
            val id = nextId++
            docs.value += document.copy(id = id)
            return id
        }
        override suspend fun rename(id: Long, title: String, now: Long) {
            docs.value = docs.value.map { if (it.id == id) it.copy(title = title, updatedAt = now) else it }
        }
        override suspend fun deleteDocument(id: Long) {
            docs.value = docs.value.filterNot { it.id == id }
            pageRows.value = pageRows.value.filterNot { it.documentId == id }
        }
        override suspend fun insertPages(pages: List<PageEntity>) {
            pageRows.value += pages
        }
        override suspend fun deletePagesOf(documentId: Long) {
            pageRows.value = pageRows.value.filterNot { it.documentId == documentId }
        }
        override suspend fun touch(id: Long, now: Long) = Unit
    }
}
