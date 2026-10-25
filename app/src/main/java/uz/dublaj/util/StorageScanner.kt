package uz.dublaj.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import timber.log.Timber
import uz.dublaj.stt.WhisperRecognizer
import java.io.File

/**
 * Telefon xotirasidan (Download, Documents va h.k.) model fayllarini avtomatik topadi
 * va ilova papkasiga nusxalaydi. Ilova o'chirilib qayta o'rnatilganda modellarni
 * qayta yuklab olish o'rniga shu yo'l bilan tiklash mumkin.
 *
 * Android 11+ da "Barcha fayllarga kirish" (MANAGE_EXTERNAL_STORAGE) ruxsati kerak.
 * Play Market uchun bu ruxsat cheklangan — foydalanuvchi SAF orqali import qilishi mumkin.
 */
object StorageScanner {

    private const val MB = 1024L * 1024L
    private val SKIP_TOP = setOf(
        "Android", "DCIM", "Pictures", "Movies", "Music", "Alarms",
        "Notifications", "Ringtones", "Podcasts", "Recordings"
    )
    private val WHISPER_BY_FILE: Map<String, WhisperRecognizer.Companion.ModelInfo> =
        WhisperRecognizer.MODEL_OPTIONS.associateBy { it.fileName }

    class ScanResult(
        val whisper: Map<String, File>,          // fayl nomi -> manba
        val vitsModel: File?,
        val vitsConfig: File?
    ) {
        val foundAny: Boolean
            get() = whisper.isNotEmpty() || vitsModel != null || vitsConfig != null
    }

    class ImportReport(val copied: Int, val found: Int, val notFound: List<String>)

    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    fun scan(): ScanResult {
        val roots = ArrayList<File>()
        Environment.getExternalStorageDirectory()?.let { roots.add(it) }
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let { roots.add(it) }
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)?.let { roots.add(it) }

        val whisper = HashMap<String, File>()
        var vitsModel: File? = null
        var vitsConfig: File? = null
        var visited = 0

        fun visit(dir: File, depth: Int) {
            if (depth > 6 || visited > 8000) return
            val list = dir.listFiles() ?: return
            for (f in list) {
                visited++
                if (f.isDirectory) {
                    if (depth == 0 && f.name in SKIP_TOP) continue
                    visit(f, depth + 1)
                } else {
                    val name = f.name
                    if (name in WHISPER_BY_FILE) {
                        whisper.putIfAbsent(name, f)
                    } else if (name == "vits_uzbek.onnx" || name.endsWith("_uzbek.onnx")) {
                        if (vitsModel == null || f.length() > (vitsModel?.length() ?: 0)) vitsModel = f
                    } else if (name == "uz_tts_base.json" || name.contains("tts") && name.endsWith(".json")) {
                        if (vitsConfig == null) vitsConfig = f
                    }
                }
            }
        }

        for (r in roots) {
            if (r.isDirectory) visit(r, 0)
        }

        Timber.i(
            "Scan: whisper=${whisper.keys} vits=${vitsModel?.path} cfg=${vitsConfig?.path} visited=$visited"
        )
        return ScanResult(whisper, vitsModel, vitsConfig)
    }

    /** Topilgan modellarni ilova papkasiga nusxalaydi (allaqachon bor va to'liq bo'lsa o'tkazib yuboradi). */
    fun importAll(context: Context, work: WorkDir, onProgress: (String) -> Unit): ImportReport {
        onProgress("Download va boshqa papkalar qidirilmoqda…")
        val r = scan()
        var copied = 0
        var found = 0
        val notFound = ArrayList<String>()

        // Whisper
        if (r.whisper.isEmpty()) notFound.add("Matn aniqlash fayli (ggml-base.bin, ggml-small.bin yoki ggml-tiny.bin)")
        for ((name, src) in r.whisper) {
            found++
            val info = WHISPER_BY_FILE.getValue(name)
            if (src.length() < info.minBytes) {
                Timber.w("Whisper $name to'liq emas (${src.length()} bayt), o'tkazildi")
                continue
            }
            val dest = File(work.models, name)
            if (isSame(src, dest)) continue
            copy(src, dest, "Matn aniqlash (${info.id})", onProgress); copied++
        }

        // VITS
        if (r.vitsModel == null) notFound.add("O'zbekcha ovoz fayli (vits_uzbek.onnx)")
        r.vitsModel?.let {
            found++
            val dest = File(work.models, "vits_uzbek.onnx")
            if (!isSame(it, dest)) { copy(it, dest, "O'zbekcha ovoz", onProgress); copied++ }
        }
        if (r.vitsConfig == null) notFound.add("Ovoz sozlama fayli (uz_tts_base.json)")
        r.vitsConfig?.let {
            found++
            val dest = File(work.models, "uz_tts_base.json")
            if (!isSame(it, dest)) { copy(it, dest, "Ovoz sozlamasi", onProgress); copied++ }
        }

        return ImportReport(copied, found, notFound)
    }

    private fun isSame(src: File, dest: File) = dest.isFile && dest.length() == src.length()

    private fun copy(src: File, dest: File, label: String, onProgress: (String) -> Unit) {
        dest.parentFile?.mkdirs()
        FileUtils.ensureSpace(dest.parentFile!!, src.length() + 50L * MB)
        val part = File(dest.parentFile, dest.name + ".part")
        val total = src.length()
        src.inputStream().use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastReport >= 4 * MB) {
                        lastReport = done
                        onProgress("$label: ${done / MB} / ${total / MB} MB")
                    }
                }
            }
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        Timber.i("Auto-import: ${src.path} → ${dest.path}")
    }
}
