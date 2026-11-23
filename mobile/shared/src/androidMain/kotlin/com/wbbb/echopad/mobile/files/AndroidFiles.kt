package com.wbbb.echopad.mobile.files

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import kotlinx.io.asSink
import kotlinx.io.asSource
import java.io.FileNotFoundException
import java.io.IOException

/** 打开要发给电脑端的文件（docs/prd/send-files.md），文件名和大小向提供文件的应用查询。有一个打不开就全部关掉，抛出异常。 */
fun ContentResolver.openOutgoingFiles(uris: List<Uri>): List<OutgoingFile> {
    val opened = mutableListOf<OutgoingFile>()
    try {
        uris.mapTo(opened, ::openOutgoingFile)
    } catch (e: Exception) {
        opened.forEach { it.close() }
        throw e
    }
    return opened
}

private fun ContentResolver.openOutgoingFile(uri: Uri): OutgoingFile {
    val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    val (name, size) = query(uri, columns, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
        cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) to size
    } ?: (null to null)
    val stream = openInputStream(uri) ?: throw FileNotFoundException("failed to open $uri")
    return OutgoingFile(name.orEmpty(), size, stream.asSource())
}

/**
 * 收到的文件存进系统的媒体库（docs/prd/send-files.md）：图片和视频进相册里的"EchoPad"相册，其他文件进"下载"。
 * 安卓 10 起不需要存储权限。写完之前标记为未完成，相册和"下载"里看不到；重名时系统自动在文件名后加序号。
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaStoreIncomingFiles(context: Context) : IncomingFiles {
    private val resolver = context.contentResolver

    override fun create(name: String): IncomingFile {
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        val (collection, folder) = when (type?.substringBefore('/')) {
            "image" -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to SavedFolder.Album
            "video" -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to SavedFolder.Album
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to SavedFolder.Downloads
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            type?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val item = resolver.insert(collection, values) ?: throw IOException("failed to create $name")
        return try {
            MediaStoreFile(item, folder, checkNotNull(resolver.openOutputStream(item)) { "failed to open $item" })
        } catch (e: Exception) {
            resolver.delete(item, null, null)
            throw e
        }
    }

    private inner class MediaStoreFile(private val item: Uri, override val folder: SavedFolder, stream: java.io.OutputStream) : IncomingFile {
        override val uri = item.toString()
        override val sink = stream.asSink()

        override fun publish() {
            sink.close()
            resolver.update(item, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }

        override fun discard() {
            sink.close()
            resolver.delete(item, null, null)
        }
    }

}

/** 在共享存储里的位置；相册是里面的"EchoPad"相册。 */
private val SavedFolder.relativePath: String
    get() = when (this) {
        SavedFolder.Album -> "${Environment.DIRECTORY_PICTURES}/EchoPad"
        SavedFolder.Downloads -> Environment.DIRECTORY_DOWNLOADS
    }

/** 用系统"文件"应用打开这个文件夹的地址（DocumentsContract 里共享存储的文件夹）。 */
val SavedFolder.documentUri: Uri
    get() = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$relativePath")
