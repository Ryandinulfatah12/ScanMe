package com.ryan.scanme.core.domain.model

import com.ryan.scanme.core.imaging.enhance.FilterSettings
import com.ryan.scanme.core.imaging.geometry.Quad

data class Document(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: List<Page>,
)

/**
 * One scanned page. Editing is non-destructive: [originalPath] is never modified; everything else
 * (crop, rotation, dewarp, filter) is re-applied from it.
 */
data class Page(
    val id: Long,
    val documentId: Long,
    val orderIndex: Int,
    val originalPath: String,
    /** Normalized outline in the original photo. */
    val quad: Quad,
    val rotationDegrees: Int,
    val dewarp: Boolean,
    val filter: FilterSettings,
    /** Camera focal length in pixels of the original photo, when known. */
    val focalLengthPx: Double?,
    val processedPath: String?,
)
