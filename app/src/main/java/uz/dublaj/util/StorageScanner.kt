package uz.dublaj.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import timber.log.Timber
import uz.dublaj.stt.WhisperRecognizer

/**
 * Foydalanuvchi tanlagan papkadan (Storage Access Framework) model fayllarini topib,
 * ilova papkasiga nusxalaydi. "Barcha fayllarga kirish" (MANAGE_EXTERNAL_STORAGE) kerak EMAS —
 * bu Google Play siyosatiga mos.
 */
object StorageScanner {

    private const val MB = 1024L * 1024L
    private val WHISPER_BY_FILE: Map<String, WhisperRecognizer.Companion.ModelInfo> =
        WhisperRecognizer.MODEL_OPTIONS.associateBy { it.fileName }

    class ScanResult(
        val whisper: Map<String, DocumentFile>,
        val vitsModel: DocumentFile?,
        val vitsConfig: DocumentFile?
    )

    class ImportReport(val copied: Int, val found: Int, val notFound: List<String>)

    private fun scan(root: DocumentFile): ScanResult {
        val whisper = HashMap<String, DocumentFile>()
        var vitsModel: DocumentFile? = null
        var vitsConfig: DocumentFile? = null
        var visited = 0

        fun visit(dir: DocumentFile, depth: Int) {
            if (depth > 4 || visited > 3000) return
            for (f in dir.listFiles()) {
                visited++
                val name = f.name ?: continue
                if (f.isDirectory) {
                    visit(f, depth + 1)
                } else if (name in WHISPER_BY_FILE) {
                    whisper.putIfAbsent(name, f)
                } else if (name == "vits_uzbek.onnx" || name.endsWith("_uzbek.onnx")) {
                    if (vitsModel == null || f.length() > (vitsModel?.length() ?: 0)) vitsModel = f
                } else if (name == "uz_tts_base.json" || (name.contains("tts") && name.endsWith(".json"))) {
                    if (vitsConfig == null) vitsConfig = f
                }
            }
        }
        visit(root, 0)
        Timber.i("Scan: whisper=${whisper.keys} vits=${vitsModel?.name} cfg=${vitsConfig?.name} visited=$visited")
        return ScanResult(whisper, vitsModel, vitsConfig)
    }

    /** Tanlangan papkadan topilgan modellarni nusxalaydi (bor va to'liq bo'lsa o'tkazib yuboradi). */
    fun importFromTree(context: Context, tree: Uri, work: WorkDir, onProgress: (String) -> Unit): ImportReport {
        val root = DocumentFile.fromTreeUri(context, tree)
            ?: throw IllegalStateException("Papka ochilmadi")
        onProgress("Papka qidirilmoqda…")
        val r = scan(root)
        var copied = 0
        var found = 0
        val notFound = ArrayList<String>()

        if (r.whisper.isEmpty()) notFound.add("Matn aniqlash fayli (ggml-base.bin, ggml-small.bin yoki ggml-tiny.bin)")
        for ((name, src) in r.whisper) {
            found++
            val info = WHISPER_BY_FILE.getValue(name)
            if (src.length() < info.minBytes) {
                Timber.w("Whisper $name to'liq emas (${src.length()} bayt), o'tkazildi")
                continue
            }
            val dest = java.io.File(work.models, name)
            if (isSame(src, dest)) continue
            copy(context, src, dest, "Matn aniqlash (${info.id})", onProgress); copied++
        }

        if (r.vitsModel == null) notFound.add("O'zbekcha ovoz fayli (vits_uzbek.onnx)")
        r.vitsModel?.let {
            found++
            val dest = java.io.File(work.models, "vits_uzbek.onnx")
            if (!isSame(it, dest)) { copy(context, it, dest, "O'zbekcha ovoz", onProgress); copied++ }
        }
        if (r.vitsConfig == null) notFound.add("Ovoz sozlama fayli (uz_tts_base.json)")
        r.vitsConfig?.let {
            found++
            val dest = java.io.File(work.models, "uz_tts_base.json")
            if (!isSame(it, dest)) { copy(context, it, dest, "Ovoz sozlamasi", onProgress); copied++ }
        }
        return ImportReport(copied, found, notFound)
    }

    private fun isSame(src: DocumentFile, dest: java.io.File) = dest.isFile && dest.length() == src.length()

    private fun copy(context: Context, src: DocumentFile, dest: java.io.File, label: String, onProgress: (String) -> Unit) {
        dest.parentFile?.mkdirs()
        val total = src.length()
        FileUtils.ensureSpace(dest.parentFile!!, total + 50L * MB)
        val part = java.io.File(dest.parentFile, dest.name + ".part")
        val input = context.contentResolver.openInputStream(src.uri)
            ?: throw IllegalStateException("Fayl ochilmadi: ${src.name}")
        input.use { ins ->
            part.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    val n = ins.read(buf)
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
        Timber.i("Import: ${src.name} → ${dest.path}")
    }
}
