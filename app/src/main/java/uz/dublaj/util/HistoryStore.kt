package uz.dublaj.util

import android.content.Context
import org.json.JSONObject
import uz.dublaj.data.AppConfig
import uz.dublaj.data.TranslationUnit
import uz.dublaj.pipeline.MontageArchive
import java.io.File

data class HistoryEntry(
    val video: File,
    val title: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val hasUzSrt: Boolean,
    val hasM4a: Boolean,
    val canEditText: Boolean = false,
    val canMontage: Boolean = false,
    val extraBytes: Long = 0L
)

/**
 * Dublyaj tarixi. Alohida baza yo'q: tayyor videolar ilova xotirasidagi output papkasida turadi,
 * yonida metama'lumot (.json), subtitr (.srt), audio (.m4a), loyiha (.dublaj, tahrirlash uchun),
 * yozilgan ovozlar (.voices/) va montaj loyihasi (.mproj/). Ilova yopilsa ham saqlanadi.
 */
object HistoryStore {
    private val EXTRAS = listOf("mp4", "json", "uz.srt", "orig.srt", "m4a", "dublaj")
    private val DIRS = listOf("voices", "mproj")

    fun projectFile(video: File) = File(video.parentFile, video.nameWithoutExtension + ".dublaj")
    fun voicesDir(video: File) = File(video.parentFile, video.nameWithoutExtension + ".voices")

    fun list(context: Context): List<HistoryEntry> {
        val work = WorkDir(context.applicationContext)
        val dir = work.output
        val files = dir.listFiles { f -> f.isFile && f.extension == "mp4" } ?: return emptyList()
        val cur = MontageArchive.currentOut(work)
        val legacy = cur == null && uz.dublaj.pipeline.MontageProject.exists(work)
        val all = files.map { f ->
            val base = f.nameWithoutExtension
            var title = ""
            var created = 0L
            val meta = File(dir, "$base.json")
            if (meta.isFile) {
                try {
                    val j = JSONObject(meta.readText())
                    title = j.optString("title")
                    created = j.optLong("created")
                } catch (_: Exception) {
                }
            }
            if (created == 0L) {
                created = Regex("dublaj_(\\d+)").find(f.name)?.groupValues?.get(1)?.toLongOrNull() ?: f.lastModified()
            }
            val mp = File(dir, "$base.mproj")
            val extra = dirSize(mp) + dirSize(File(dir, "$base.voices"))
            HistoryEntry(
                video = f, title = title, createdAt = created, sizeBytes = f.length(),
                hasUzSrt = File(dir, "$base.uz.srt").isFile,
                hasM4a = File(dir, "$base.m4a").isFile,
                canEditText = projectFile(f).isFile || (File(dir, "$base.uz.srt").isFile && File(dir, "$base.orig.srt").isFile),
                canMontage = File(mp, "project.json").isFile || cur == f.name,
                extraBytes = extra
            )
        }.sortedByDescending { it.createdAt }
        return if (legacy) all.mapIndexed { i, e -> if (i == 0) e.copy(canMontage = true) else e } else all
    }

    private fun dirSize(f: File): Long =
        if (f.isFile) f.length() else (f.listFiles()?.sumOf { dirSize(it) } ?: 0L)

    fun writeMeta(video: File, title: String, durationSec: Double, segments: Int, src: String = "") {
        val j = JSONObject()
            .put("title", title)
            .put("created", System.currentTimeMillis())
            .put("durationSec", durationSec)
            .put("segments", segments)
            .put("src", src)
        File(video.parentFile, video.nameWithoutExtension + ".json").writeText(j.toString())
    }

    /** Dublyajga ishlatilgan asl video (ilova ichidagi nusxa). Tozalab yuborilgan bo'lsa null. */
    fun srcOf(video: File): File? = try {
        val m = File(video.parentFile, video.nameWithoutExtension + ".json")
        val p = if (m.isFile) JSONObject(m.readText()).optString("src") else ""
        if (p.isNotBlank()) File(p).takeIf { it.isFile } else null
    } catch (_: Exception) {
        null
    }

    /** Tahrirlash uchun: tarjima, rollar, ovoz usullari va sozlamalar + yozilgan ovozlar. */
    fun saveProject(video: File, title: String, config: AppConfig, units: List<TranslationUnit>, work: WorkDir) {
        projectFile(video).writeText(ProjectFile.toJson(title, config, units), Charsets.UTF_8)
        val vd = voicesDir(video)
        vd.deleteRecursively()
        val rec = units.filter { it.hasUserVoice && work.hasSegmentVoice(it.id) }
        if (rec.isEmpty()) return
        vd.mkdirs()
        rec.forEach { work.segmentVoice(it.id).copyTo(File(vd, "seg_voice_${it.id}.wav"), overwrite = true) }
    }

    /** Montajdan yangi video chiqqanda: tahrirlash ma'lumotlari ham yangi videoga ko'chiriladi. */
    fun copyProject(from: File, to: File) {
        try {
            val pf = projectFile(from)
            if (pf.isFile) pf.copyTo(projectFile(to), overwrite = true)
            val vd = voicesDir(from)
            if (vd.isDirectory) vd.copyRecursively(voicesDir(to), overwrite = true)
        } catch (_: Exception) {
        }
    }

    /**
     * Tarixdagi video uchun tahrirlanadigan loyiha. Yangi videolarda .dublaj fayli bor;
     * eskilarida faqat subtitrlar (asl + o'zbekcha) — shulardan gaplar tiklanadi.
     */
    fun loadProject(video: File): ProjectFile.Data? {
        val pf = projectFile(video)
        if (pf.isFile) {
            try {
                val d = ProjectFile.parse(pf.readText(Charsets.UTF_8))
                val vd = voicesDir(video)
                val units = d.units.map { u ->
                    if (File(vd, "seg_voice_${u.id}.wav").let { it.isFile && it.length() > 44 }) u.copy(hasUserVoice = true) else u
                }
                return ProjectFile.Data(d.title, units, d.settings)
            } catch (_: Exception) {
            }
        }
        return try {
            val uz = SrtParser.parse(ExportUtils.srtFile(video, true).readText(Charsets.UTF_8))
            val orig = SrtParser.parse(ExportUtils.srtFile(video, false).readText(Charsets.UTF_8))
            if (uz.isEmpty()) return null
            val units = uz.mapIndexed { i, c ->
                val o = orig.minByOrNull { kotlin.math.abs(it.start - c.start) }
                    ?.takeIf { kotlin.math.abs(it.start - c.start) < 1.5 }
                TranslationUnit(
                    id = i, start = c.start, end = c.end,
                    text = o?.text ?: c.text, uzText = c.text
                )
            }
            ProjectFile.Data(video.nameWithoutExtension, units, JSONObject())
        } catch (_: Exception) {
            null
        }
    }

    /** Yozilgan ovozlarni (.voices/) ish papkasiga qaytaradi. */
    fun restoreVoices(video: File, work: WorkDir) {
        work.temp.listFiles { f -> f.name.startsWith("seg_voice_") && f.name.endsWith(".wav") }?.forEach { it.delete() }
        val vd = voicesDir(video)
        vd.listFiles { f -> f.name.startsWith("seg_voice_") && f.name.endsWith(".wav") }?.forEach {
            try { it.copyTo(File(work.temp, it.name), overwrite = true) } catch (_: Exception) { }
        }
    }

    /** Videoni va unga tegishli hamma fayllarni (meta, srt, m4a, loyiha, ovozlar, montaj) o'chiradi. */
    fun delete(video: File) {
        val base = video.nameWithoutExtension
        EXTRAS.forEach { ext -> File(video.parentFile, "$base.$ext").delete() }
        DIRS.forEach { ext -> File(video.parentFile, "$base.$ext").deleteRecursively() }
    }

    fun deleteAll(context: Context) {
        list(context).forEach { delete(it.video) }
    }

    fun totalBytes(entries: List<HistoryEntry>): Long = entries.sumOf { it.sizeBytes + it.extraBytes }
}

/** Eng sodda .srt o'qigich: raqam, vaqt qatori, matn (bo'sh qator bilan ajratilgan). */
object SrtParser {
    data class Cue(val start: Double, val end: Double, val text: String)

    private val TIME = Regex("(\\d+):(\\d+):(\\d+)[,.](\\d+)\\s*-->\\s*(\\d+):(\\d+):(\\d+)[,.](\\d+)")

    private fun sec(h: String, m: String, s: String, ms: String): Double =
        h.toInt() * 3600.0 + m.toInt() * 60.0 + s.toInt() + ms.padEnd(3, '0').take(3).toInt() / 1000.0

    fun parse(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        for (block in text.replace("\r", "").trim().split(Regex("\\n\\s*\\n"))) {
            val lines = block.lines()
            val ti = lines.indexOfFirst { TIME.containsMatchIn(it) }
            if (ti < 0) continue
            val g = TIME.find(lines[ti])!!.groupValues
            val body = lines.drop(ti + 1).joinToString(" ") { it.trim() }.trim()
            if (body.isEmpty()) continue
            out.add(Cue(sec(g[1], g[2], g[3], g[4]), sec(g[5], g[6], g[7], g[8]), body))
        }
        return out
    }
}
