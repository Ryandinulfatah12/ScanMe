package com.ryan.scanme.core.domain.repository

import com.ryan.scanme.core.domain.model.Document
import com.ryan.scanme.core.domain.model.Page
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    fun observeDocuments(): Flow<List<Document>>
    fun observeDocument(id: Long): Flow<Document?>
    suspend fun createDocument(title: String): Long
    suspend fun rename(documentId: Long, title: String)
    suspend fun delete(documentId: Long)
    suspend fun addPage(page: Page): Long
    suspend fun updatePage(page: Page)
    suspend fun reorderPages(documentId: Long, pageIdsInOrder: List<Long>)
    suspend fun deletePage(pageId: Long)
}
