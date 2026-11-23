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
    val originalPath: String,
    /** Clockwise rotation that makes the stored photo upright (camera sensor orientation / EXIF). */
    val photoRotation: Int,
    /** Normalized outline in the upright photo. */
    val quad: Quad,
    val rotationDegrees: Int,
    val dewarp: Boolean,
    val filter: FilterSettings,
    /** Camera focal length in pixels of the original photo, when known. */
    val focalLengthPx: Double?,
    /** Small JPEG of the filtered page for lists; may be missing. */
    val thumbnailPath: String?,
)
