package uz.dublaj.util

import android.content.Context
import android.net.Uri
import timber.log.Timber
import java.io.File

/** Telefondagi fayllardan (SAF orqali tanlangan) modellarni ilova papkasiga nusxalaydi. */
object ModelImporter {

    fun importFile(context: Context, uri: Uri, dest: File, minBytes: Long = 1000): File {
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        context.contentResolver.openInputStream(uri)?.use { input ->
            part.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
        } ?: throw IllegalStateException("Fayl ochilmadi")
        if (part.length() < minBytes) {
            part.delete()
            throw IllegalStateException("Fayl bo'sh yoki juda kichik")
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        return dest
    }
}
