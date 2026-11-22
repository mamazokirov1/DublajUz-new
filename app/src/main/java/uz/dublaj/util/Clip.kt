package uz.dublaj.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build

object Clip {
    private fun cm(c: Context): ClipboardManager? =
        c.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    fun copy(c: Context, text: String): Boolean = try {
        val m = cm(c) ?: return false
        m.setPrimaryClip(ClipData.newPlainText("DublajUz", text))
        true
    } catch (_: Exception) {
        false
    }

    fun paste(c: Context): String? = try {
        val m = cm(c)
        val d = m?.primaryClip
        if (d == null || d.itemCount == 0) null
        else d.getItemAt(0).coerceToText(c.applicationContext)?.toString()?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /** Android 13+ nusxalaganda o'zi xabar ko'rsatadi: takroriy toast kerak emas. */
    val ownToast: Boolean get() = Build.VERSION.SDK_INT >= 33
}
