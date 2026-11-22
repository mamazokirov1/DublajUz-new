package uz.dublaj.pipeline

import org.json.JSONObject
import timber.log.Timber
import uz.dublaj.util.WorkDir
import java.io.File

/**
 * Montaj loyihasi faqat oxirgi dublyaj uchun temp/dubproj da turadi. Yangi dublyaj boshlanganda eski loyiha
 * o'chib ketmasligi uchun u tayyor videoning yoniga (<nom>.mproj) ko'chiriladi, tarixdan qayta ochilganda
 * esa joyiga qaytariladi. Ko'chirish rename bilan: tez va qo'shimcha joy olmaydi.
 */
object MontageArchive {

    fun dirOf(video: File): File = File(video.parentFile, video.nameWithoutExtension + ".mproj")

    fun hasArchived(video: File): Boolean = File(dirOf(video), "project.json").isFile

    private fun moveDir(src: File, dst: File) {
        dst.deleteRecursively()
        if (!src.renameTo(dst)) {
            src.copyRecursively(dst, overwrite = true)
            src.deleteRecursively()
        }
    }

    /** Joriy (temp) loyiha qaysi videoga tegishli: output/ dagi fayl nomi yoki null. */
    fun currentOut(work: WorkDir): String? =
        try { MontageProject.load(work)?.outName?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }

    /** Joriy loyihani o'z videosining yoniga ko'chiradi. Video yo'q bo'lsa yoki loyiha yo'q bo'lsa hech narsa qilmaydi. */
    fun archiveCurrent(work: WorkDir) {
        try {
            val name = currentOut(work) ?: return
            val vid = File(work.output, name)
            if (!vid.isFile) return
            File(work.dubProject, "preview.wav").delete()
            File(work.dubProject, "rec_own.wav").delete()
            work.dubProject.listFiles { f -> f.name.endsWith(".new") || f.name.startsWith("undo_") }?.forEach { it.delete() }
            moveDir(work.dubProject, dirOf(vid))
        } catch (e: Exception) {
            Timber.w(e, "Montaj loyihasi arxivlanmadi")
        }
    }

    /**
     * [video] ning arxivdagi loyihasini joriy qiladi (avval joriysi arxivlanadi).
     * Asl video topilmasa, tayyor videoning o'zi tasvir manbai bo'ladi (audio baribir loyihadan olinadi).
     */
    fun restore(work: WorkDir, video: File): Boolean {
        val cur = currentOut(work)
        if (cur == video.name) return true
        val src = dirOf(video)
        if (!File(src, "project.json").isFile) {
            // eski versiyada saqlangan joriy loyiha (video nomi yozilmagan): shu videoniki deb belgilanadi
            if (cur == null && MontageProject.exists(work)) { stamp(work, video); return true }
            return false
        }
        return try {
            archiveCurrent(work)
            moveDir(src, work.dubProject)
            val f = File(work.dubProject, "project.json")
            val j = JSONObject(f.readText())
            if (!File(j.optString("video")).isFile) j.put("video", video.absolutePath)
            j.put("out", video.name)
            f.writeText(j.toString())
            true
        } catch (e: Exception) {
            Timber.w(e, "Montaj loyihasi tiklanmadi")
            false
        }
    }

    private fun stamp(work: WorkDir, video: File) {
        try {
            val f = File(work.dubProject, "project.json")
            val j = JSONObject(f.readText())
            j.put("out", video.name)
            f.writeText(j.toString())
        } catch (e: Exception) {
            Timber.w(e, "Montaj loyihasi belgilanmadi")
        }
    }

    /** Tarix ro'yxati uchun: shu video montajini ochish mumkinmi. */
    fun available(work: WorkDir, video: File): Boolean = hasArchived(video) || currentOut(work) == video.name
}
