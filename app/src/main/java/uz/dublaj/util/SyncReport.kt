package uz.dublaj.util

import android.content.Context
import java.io.File

/** Sinxronlash (vaqt) hisoboti: muammo hisobotiga qo'shiladi, shunda kechikish/erta ketish sababini ko'rish mumkin. */
object SyncReport {
    private fun file(context: Context): File =
        File(context.cacheDir, "reports/sync_report.txt").also { it.parentFile?.mkdirs() }

    fun f(x: Double): String = String.format(java.util.Locale.US, "%.2f", x)
    fun sf(x: Double): String = String.format(java.util.Locale.US, "%+.2f", x)

    fun reset(context: Context, header: String) {
        try {
            file(context).writeText(header + "\n")
        } catch (_: Exception) {
        }
    }

    fun add(context: Context, line: String) {
        try {
            file(context).appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    fun read(context: Context): String = try {
        val f = file(context)
        if (f.isFile) f.readText().takeLast(14000) else ""
    } catch (_: Exception) {
        ""
    }
}
