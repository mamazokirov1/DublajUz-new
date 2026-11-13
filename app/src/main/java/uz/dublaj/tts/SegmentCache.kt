package uz.dublaj.tts

import timber.log.Timber
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Gap ovozlari keshi. Bitta gap tahrirlanganda butun video qayta ovozlanmaydi:
 * matni, roli, ovoz sozlamalari va ajratilgan vaqt o'zgarmagan gaplar diskdan olinadi.
 *
 * Kesh kaliti: [paramKey] (model + sozlamalar) + gap matni + ovoz balandligi.
 * VITS gap vaqtiga sig'masa tezlashtirib qayta sintez qiladi, shuning uchun
 * "siqilgan" yozuv faqat deyarli bir xil vaqt oralig'ida qayta ishlatiladi.
 */
class SegmentCache(private val dir: File, private val paramKey: String) {

    init { dir.mkdirs() }

    private fun fileFor(text: String, pitch: Float): File {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(paramKey.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(String.format(Locale.US, "%.2f", pitch).toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(text.toByteArray(Charsets.UTF_8))
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        return File(dir, "$hex.seg")
    }

    /**
     * @param slotSec gap uchun hozirgi bo'sh vaqt
     * @param slotDependent false bo'lsa (Chatterbox) vaqt natijaga ta'sir qilmaydi
     * @param canCompress VITS bu sozlamalarda gapni tezlashtira oladimi
     */
    fun get(text: String, pitch: Float, slotSec: Double, slotDependent: Boolean, canCompress: Boolean): VitsSynthesizer.SegmentAudio? {
        val f = fileFor(text, pitch)
        if (!f.isFile) return null
        return try {
            DataInputStream(f.inputStream().buffered()).use { inp ->
                if (inp.readInt() != MAGIC) return null
                val sr = inp.readInt()
                val scale = inp.readFloat()
                val slotUsed = inp.readDouble()
                val compressed = inp.readBoolean()
                val n = inp.readInt()
                if (n < 0 || n > 60 * sr * 5) return null
                val dur = n.toDouble() / sr
                val reusable = !slotDependent ||
                    (!compressed && (dur <= slotSec || !canCompress)) ||
                    (compressed && kotlin.math.abs(slotSec - slotUsed) <= 0.03)
                if (!reusable) return null
                val bytes = ByteArray(n * 2)
                inp.readFully(bytes)
                val pcm = ShortArray(n) { i ->
                    ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xFF)).toShort()
                }
                f.setLastModified(System.currentTimeMillis())   // LRU: ishlatilganini belgilaymiz
                VitsSynthesizer.SegmentAudio(pcm, sr, scale)
            }
        } catch (e: Exception) {
            Timber.w(e, "Kesh o'qilmadi: ${f.name}")
            f.delete()
            null
        }
    }

    fun put(text: String, pitch: Float, slotSec: Double, seg: VitsSynthesizer.SegmentAudio, compressed: Boolean) {
        if (seg.pcm.isEmpty()) return
        val f = fileFor(text, pitch)
        val tmp = File(dir, f.name + ".tmp")
        try {
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(seg.sampleRate)
                out.writeFloat(seg.lengthScale)
                out.writeDouble(slotSec)
                out.writeBoolean(compressed)
                out.writeInt(seg.pcm.size)
                val bytes = ByteArray(seg.pcm.size * 2)
                for (i in seg.pcm.indices) {
                    val v = seg.pcm[i].toInt()
                    bytes[2 * i] = v.toByte()
                    bytes[2 * i + 1] = (v shr 8).toByte()
                }
                out.write(bytes)
            }
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) tmp.delete()
        } catch (e: Exception) {
            Timber.w(e, "Kesh yozilmadi")
            tmp.delete()
        }
    }

    /** Hajm [maxBytes] dan oshsa, eng eskilarini o'chiradi. */
    fun prune(maxBytes: Long = MAX_BYTES) {
        val files = dir.listFiles { x -> x.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes * 8 / 10) break
            total -= f.length()
            f.delete()
        }
    }

    companion object {
        private const val MAGIC = 0x44554231          // "DUB1"
        const val MAX_BYTES = 250L * 1024 * 1024

        /** Hamma kesh yozuvlari shu qiymatga bog'liq bo'lib, undagi har qanday o'zgarish eski keshni yaroqsiz qiladi. */
        fun fileSig(f: File?): String = if (f != null && f.isFile) "${f.length()}:${f.lastModified()}" else "-"
    }
}
