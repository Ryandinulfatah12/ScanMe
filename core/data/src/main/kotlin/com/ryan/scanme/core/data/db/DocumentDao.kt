package com.ryan.scanme.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY updatedAt DESC")
    fun observeDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observeDocument(id: Long): Flow<DocumentEntity?>

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY orderIndex")
    fun observePages(documentId: Long): Flow<List<PageEntity>>

    @Insert
    suspend fun insertDocument(document: DocumentEntity): Long

    @Query("UPDATE documents SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, title: String, now: Long)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: Long)

    @Insert
    suspend fun insertPage(page: PageEntity): Long

    @Update
    suspend fun updatePage(page: PageEntity)

    @Query("UPDATE pages SET orderIndex = :index WHERE id = :pageId")
    suspend fun setPageOrder(pageId: Long, index: Int)

    @Transaction
    suspend fun reorder(pageIdsInOrder: List<Long>) {
        pageIdsInOrder.forEachIndexed { index, id -> setPageOrder(id, index) }
    }

    @Query("DELETE FROM pages WHERE id = :pageId")
    suspend fun deletePage(pageId: Long)
}
