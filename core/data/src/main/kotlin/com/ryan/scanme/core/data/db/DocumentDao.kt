package com.ryan.scanme.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY updatedAt DESC")
    fun observeDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM pages ORDER BY documentId, orderIndex")
    fun observeAllPages(): Flow<List<PageEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun document(id: Long): DocumentEntity?

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY orderIndex")
    suspend fun pages(documentId: Long): List<PageEntity>

    @Insert
    suspend fun insertDocument(document: DocumentEntity): Long

    @Query("UPDATE documents SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, title: String, now: Long)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: Long)

    @Insert
    suspend fun insertPages(pages: List<PageEntity>)

    @Query("DELETE FROM pages WHERE documentId = :documentId")
    suspend fun deletePagesOf(documentId: Long)

    @Query("UPDATE documents SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    /** Pages are small rows; rewriting them all keeps order, deletions and edits consistent in one step. */
    @Transaction
    suspend fun replacePages(documentId: Long, pages: List<PageEntity>, now: Long) {
        deletePagesOf(documentId)
        insertPages(pages)
        touch(documentId, now)
    }
}
