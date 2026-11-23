package com.ryan.scanme.core.data.repository

import android.content.Context
import com.ryan.scanme.core.data.db.DocumentDao
import com.ryan.scanme.core.data.db.DocumentEntity
import com.ryan.scanme.core.data.db.PageEntity
import com.ryan.scanme.core.domain.model.Document
import com.ryan.scanme.core.domain.model.Page
import com.ryan.scanme.core.domain.repository.DocumentRepository
import com.ryan.scanme.core.imaging.enhance.FilterPreset
import com.ryan.scanme.core.imaging.enhance.FilterSettings
import com.ryan.scanme.core.imaging.geometry.Quad
import com.ryan.scanme.core.imaging.geometry.Vec2
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Documents in Room; their photos and thumbnails under `<root>/<documentId>/` (app-private storage, so
 * they survive cache clearing and need no permission).
 */
@Singleton
class RoomDocumentRepository internal constructor(
    private val dao: DocumentDao,
    private val root: File,
    private val clock: () -> Long,
) : DocumentRepository {

    @Inject
    constructor(dao: DocumentDao, @ApplicationContext context: Context) :
        this(dao, File(context.filesDir, "documents"), System::currentTimeMillis)

    override fun observeDocuments(): Flow<List<Document>> =
        combine(dao.observeDocuments(), dao.observeAllPages()) { docs, pages ->
            val byDoc = pages.groupBy { it.documentId }
            docs.map { it.toDomain(byDoc[it.id].orEmpty()) }
        }

    override suspend fun document(id: Long): Document? = withContext(Dispatchers.IO) {
        dao.document(id)?.toDomain(dao.pages(id))
    }

    override suspend fun save(documentId: Long?, title: String, pages: List<Page>): Long = withContext(Dispatchers.IO) {
        val now = clock()
        val id = documentId ?: dao.insertDocument(DocumentEntity(title = title, createdAt = now, updatedAt = now))
        val dir = dirOf(id).apply { mkdirs() }
        val entities = pages.mapIndexed { i, p ->
            p.toEntity(
                documentId = id,
                orderIndex = i,
                originalPath = adopt(File(p.originalPath), dir),
                // A thumbnail is only a cache: a missing one is dropped, not an error.
                thumbnailPath = p.thumbnailPath?.let(::File)?.takeIf { it.exists() }?.let { adopt(it, dir) },
            )
        }
        dao.replacePages(id, entities, now)
        // Only after the rows are committed: a failed save never loses files the old rows point to.
        val keep = entities.flatMap { listOfNotNull(it.originalPath, it.thumbnailPath) }.map { File(it).canonicalPath }.toSet()
        dir.listFiles()?.filter { it.canonicalPath !in keep }?.forEach { it.delete() }
        id
    }

    override suspend fun rename(documentId: Long, title: String) = withContext(Dispatchers.IO) {
        dao.rename(documentId, title, clock())
    }

    override suspend fun delete(documentId: Long) = withContext(Dispatchers.IO) {
        dao.deleteDocument(documentId)
        dirOf(documentId).deleteRecursively()
        Unit
    }

    private fun dirOf(id: Long) = File(root, id.toString())

    /** [file] if it already lives in [dir], otherwise a copy in [dir]. */
    private fun adopt(file: File, dir: File): String {
        if (file.parentFile?.canonicalPath == dir.canonicalPath) return file.path
        val target = File(dir, "${UUID.randomUUID()}.${file.extension.ifEmpty { "jpg" }}")
        file.copyTo(target)
        return target.path
    }
}

internal fun DocumentEntity.toDomain(pages: List<PageEntity>) = Document(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    pages = pages.sortedBy { it.orderIndex }.map { it.toDomain() },
)

internal fun PageEntity.toDomain() = Page(
    originalPath = originalPath,
    photoRotation = photoRotation,
    quad = decodeQuad(quad),
    rotationDegrees = rotationDegrees,
    dewarp = dewarp,
    filter = FilterSettings(
        preset = FilterPreset.entries.firstOrNull { it.name == filterPreset } ?: FilterPreset.AUTO_COLOR,
        intensity = filterIntensity,
    ),
    focalLengthPx = focalLengthPx,
    thumbnailPath = thumbnailPath,
)

internal fun Page.toEntity(documentId: Long, orderIndex: Int, originalPath: String, thumbnailPath: String?) = PageEntity(
    documentId = documentId,
    orderIndex = orderIndex,
    originalPath = originalPath,
    photoRotation = photoRotation,
    quad = encodeQuad(quad),
    rotationDegrees = rotationDegrees,
    dewarp = dewarp,
    filterPreset = filter.preset.name,
    filterIntensity = filter.intensity,
    focalLengthPx = focalLengthPx,
    thumbnailPath = thumbnailPath,
)

internal fun encodeQuad(q: Quad): String = q.corners.joinToString(",") { "${it.x},${it.y}" }

/** Falls back to the full frame for a malformed value, so one bad row cannot crash the document list. */
internal fun decodeQuad(s: String): Quad {
    val v = s.split(',').mapNotNull { it.toDoubleOrNull() }
    if (v.size != 8) return Quad.FULL_FRAME
    return Quad(Vec2(v[0], v[1]), Vec2(v[2], v[3]), Vec2(v[4], v[5]), Vec2(v[6], v[7]))
}
