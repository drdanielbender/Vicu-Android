package com.rendyhd.vicu.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

object FileUtils {

    fun getFileNameAndBytes(context: Context, uri: Uri): Pair<String, ByteArray>? {
        return try {
            val contentResolver = context.contentResolver
            val fileName = getFileName(context, uri) ?: "file"
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val bytes = inputStream.use { it.readBytes() }
            Pair(fileName, bytes)
        } catch (_: Exception) {
            null
        }
    }

    fun getDisplayName(context: Context, uri: Uri): String? = getFileName(context, uri)

    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        }
        if (name == null) {
            name = uri.lastPathSegment
        }
        return name
    }
}
