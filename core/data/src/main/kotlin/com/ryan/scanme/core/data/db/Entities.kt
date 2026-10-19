package com.ryan.scanme.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId")],
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val orderIndex: Int,
    val originalPath: String,
    /** tl.x,tl.y,tr.x,tr.y,br.x,br.y,bl.x,bl.y (normalized). */
    val quad: String,
    val rotationDegrees: Int,
    val dewarp: Boolean,
    val filterPreset: String,
    val filterIntensity: Double,
    val focalLengthPx: Double?,
    val processedPath: String?,
    /** Bumped whenever geometry changes, invalidating the corrected/processed caches. */
    val processedVersion: Int,
)
