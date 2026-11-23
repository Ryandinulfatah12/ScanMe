package com.ryan.scanme.core.domain.repository

import com.ryan.scanme.core.domain.model.Document
import com.ryan.scanme.core.domain.model.Page
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    /** Newest first, with pages. */
    fun observeDocuments(): Flow<List<Document>>

    suspend fun document(id: Long): Document?

    /**
     * Saves [pages] (in order) as the full content of document [documentId], or of a new document titled
     * [title] when [documentId] is null. Files outside the document's storage (camera cache) are copied in;
     * files of the document no longer referenced are deleted. Returns the document id.
     */
    suspend fun save(documentId: Long?, title: String, pages: List<Page>): Long

    suspend fun rename(documentId: Long, title: String)

    /** Deletes the document and its stored photos. */
    suspend fun delete(documentId: Long)
}
