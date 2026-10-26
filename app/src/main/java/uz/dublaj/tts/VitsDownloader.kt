package uz.dublaj.tts

import org.json.JSONObject
import timber.log.Timber
import uz.dublaj.BuildConfig
import uz.dublaj.util.Downloader
import uz.dublaj.util.WorkDir
import java.io.File
import java.security.MessageDigest

/**
 * O'zbekcha ovoz modelini (vits_uzbek.onnx + uz_tts_base.json) GitHub Release'dan yuklaydi.
 *
 * Fayllar repo'ning `models-v1` relizida turadi (pre-release sifatida):
 *   https://github.com/<owner>/<repo>/releases/download/models-v1/<fayl>
 * Reliz ichida ixtiyoriy `models-manifest.json` bo'lsa, hajm va SHA-256 tekshiriladi
 * (tools/publish_models.sh uni o'zi yaratadi).
 */
object VitsDownloader {

    const val RELEASE_TAG = "models-v1"
    private const val MANIFEST = "models-manifest.json"

    fun baseUrl(): String = "https://github.com/${BuildConfig.GITHUB_REPO}/releases/download/$RELEASE_TAG"

    private class Meta(val size: Long, val sha256: String)

    private fun loadManifest(dir: File): Map<String, Meta> = try {
        val f = File(dir, MANIFEST)
        Downloader.download("${baseUrl()}/$MANIFEST", f, minBytes = 2)
        val root = JSONObject(f.readText())
        val files = root.optJSONObject("files") ?: root
        files.keys().asSequence().associateWith { k ->
            val o = files.getJSONObject(k)
            Meta(o.optLong("size", 0L), o.optString("sha256", "").lowercase())
        }
    } catch (e: Exception) {
        Timber.w(e, "Manifest o'qilmadi — tekshiruvsiz yuklanadi")
        emptyMap()
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun valid(f: File, meta: Meta?, minBytes: Long): Boolean {
        if (!f.isFile || f.length() < minBytes) return false
        if (meta != null && meta.size > 0 && f.length() != meta.size) return false
        return true
    }

    /**
     * Ikkala faylni yuklaydi (mavjud va to'liq bo'lsa o'tkazib yuboradi).
     * Bloklovchi: IO oqimida chaqiring. onProgress: (yuklangan, jami) — jami noma'lum bo'lsa -1.
     */
    fun download(work: WorkDir, onProgress: (Long, Long) -> Unit) {
        val (model, cfg) = VitsSynthesizer.modelFiles(work)
        val manifest = loadManifest(work.models)

        val jobs = listOf(
            Triple(model, 100_000L, "vits_uzbek.onnx"),
            Triple(cfg, 10L, "uz_tts_base.json")
        )
        for ((dest, minBytes, name) in jobs) {
            val meta = manifest[name]
            if (valid(dest, meta, minBytes)) continue
            Timber.i("Ovoz modeli yuklanmoqda: ${baseUrl()}/$name")
            Downloader.download("${baseUrl()}/$name", dest, minBytes, onProgress)
            if (meta != null && meta.sha256.isNotBlank()) {
                val actual = sha256(dest)
                if (actual != meta.sha256) {
                    dest.delete()
                    throw IllegalStateException("$name buzilgan (SHA-256 mos kelmadi). Qayta urinib ko'ring.")
                }
            }
        }
    }
}
