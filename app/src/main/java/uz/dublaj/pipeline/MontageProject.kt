package uz.dublaj.pipeline

import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import uz.dublaj.util.WorkDir
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Montajdagi bitta dublyaj klipi (tayyor, tezlashtirilgan PCM) va uning joyi. */
class MontageClip(
    val id: Int,
    /** Hozirgi joyi (soniya). Montajda o'zgartiriladi. */
    var atSec: Double,
    /** Dublyaj paytidagi dastlabki joyi (qayta tiklash uchun). */
    val at0Sec: Double,
    /** Qayta ovozlanganda o'zgaradi. */
    @Volatile var samples: Int,
    @Volatile var speed: Float,
    /** Asl nutqning boshi/oxiri (siljishsiz). */
    val origStart: Double,
    val origEnd: Double,
    @Volatile var text: String,
    val file: File,
    /** Gap izohi: "" | "erkak" | "ayol" | "bola" (qayta ovozlashda boshlang'ich tanlov). */
    @Volatile var role: String = ""
) {
    fun durSec(sr: Int): Double = samples.toDouble() / sr
    fun placeSample(sr: Int): Long = (atSec * sr).roundToInt().toLong().coerceAtLeast(0)
}

/**
 * Dublyajdan keyin saqlanadigan montaj loyihasi: temp/dubproj/{project.json, orig.wav, clip_<id>.pcm, preview.wav}.
 * Tinglash va yakuniy video uchun hamma gap qayta ovozlanmaydi, faqat joylari o'zgaradi.
 */
class MontageProject(
    val dir: File,
    val videoPath: String,
    val sr: Int,
    val duration: Double,
    val title: String,
    val clips: List<MontageClip>
) {
    /** Shu loyiha qaysi tayyor videoga (output/ ichidagi fayl nomi) tegishli. Tarixdan qayta ochish uchun. */
    @Volatile var outName: String = ""

    val origWav: File get() = File(dir, "orig.wav")
    val previewWav: File get() = File(dir, "preview.wav")
    private val json: File get() = File(dir, "project.json")

    fun toRenderClips(): List<SegmentPlacer.Clip> =
        clips.map { SegmentPlacer.Clip(it.id, it.placeSample(sr), it.samples, it.file, it.speed) }

    /** Dublyaj paytidagi kabi: asl nutq oralig'ida ham asl ovoz pasaytiriladi. */
    fun origRanges(): List<Pair<Long, Long>> =
        clips.map { Pair((max(0.0, it.origStart - 0.05) * sr).toLong(), (it.origEnd * sr).toLong()) }

    @Synchronized
    fun save() {
        val arr = JSONArray()
        for (c in clips) {
            arr.put(JSONObject().apply {
                put("id", c.id); put("at", c.atSec); put("at0", c.at0Sec)
                put("samples", c.samples); put("speed", c.speed.toDouble())
                put("os", c.origStart); put("oe", c.origEnd); put("text", c.text); put("role", c.role)
            })
        }
        val root = JSONObject()
            .put("v", 1).put("video", videoPath).put("sr", sr)
            .put("duration", duration).put("title", title).put("out", outName).put("clips", arr)
        val tmp = File(dir, "project.json.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(json)) { json.writeText(root.toString()); tmp.delete() }
    }

    companion object {
        fun exists(work: WorkDir): Boolean =
            File(work.dubProject, "project.json").isFile && File(work.dubProject, "orig.wav").isFile

        fun load(work: WorkDir): MontageProject? = try {
            val dir = work.dubProject
            val f = File(dir, "project.json")
            if (!f.isFile || !File(dir, "orig.wav").isFile) null
            else {
                val r = JSONObject(f.readText())
                val arr = r.getJSONArray("clips")
                val clips = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val id = o.getInt("id")
                    val cf = File(dir, "clip_$id.pcm")
                    if (!cf.isFile) null
                    else MontageClip(
                        id, o.getDouble("at"), o.optDouble("at0", o.getDouble("at")),
                        o.getInt("samples"), o.optDouble("speed", 1.0).toFloat(),
                        o.optDouble("os", 0.0), o.optDouble("oe", 0.0), o.optString("text", ""), cf,
                        o.optString("role", "")
                    )
                }
                if (clips.isEmpty()) null
                else MontageProject(dir, r.getString("video"), r.getInt("sr"), r.getDouble("duration"), r.optString("title", ""), clips)
                    .also { it.outName = r.optString("out", "") }
            }
        } catch (e: Exception) {
            Timber.w(e, "Montaj loyihasi o'qilmadi")
            null
        }
    }
}
