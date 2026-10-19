package com.ryan.scanme.core.data.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GalleryExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Saves [bitmap] as JPEG. Android 10+: Pictures/ScanMe in the shared gallery (no permission needed).
     * Android 7-9: the app's own Pictures folder, which needs no storage permission either.
     */
    fun saveJpeg(bitmap: Bitmap, displayName: String, quality: Int = 92): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ScanMe")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                    ?: throw IOException("Cannot open $uri")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        val file = File(dir, "$displayName.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return Uri.fromFile(file)
    }
}
