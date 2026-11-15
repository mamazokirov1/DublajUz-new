package uz.dublaj.tts

import org.json.JSONObject
import timber.log.Timber
import uz.dublaj.data.AppConfig
import uz.dublaj.text.TextNormalizer
import uz.dublaj.translate.splitApiKeys
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Gemini TTS ishlatilsinmi: dvigatel tanlangan va kamida bitta kalit bor. */
fun AppConfig.useGeminiTts(): Boolean = voiceEngine == "gemini" && splitApiKeys(geminiKey).isNotEmpty()

/** Rolga mos Gemini ovozi. Bo'sh rol = «asosiy ovoz» (baseVoiceFemale bo'yicha). */
fun AppConfig.gemVoiceFor(role: String): String = when (role) {
    "erkak" -> gemVoiceMale
    "ayol" -> gemVoiceFemale
    "bola" -> gemVoiceChild
    else -> if (baseVoiceFemale) gemVoiceFemale else gemVoiceMale
}

/**
 * Gemini TTS (gemini-3.8-*-tts): o'zbek tilini (Northern Uzbek) qo'llab-quvvatlaydi.
 * Foydalanuvchining o'z Gemini API kaliti ishlatiladi (bir nechta bo'lsa, limit tugaganda keyingisiga o'tadi).
 * Javob: 24 kHz, mono, 16 bit PCM (WAV yoki xom PCM).
 */
class GeminiTtsClient(apiKeys: String, private val model: String) {

    class TtsException(
        message: String,
        val code: Int = 0,
        val fatal: Boolean = false,
        val keyIssue: Boolean = false
    ) : Exception(message)

    private val keys: List<String> = splitApiKeys(apiKeys)

    private fun pickKey(): String {
        val now = System.currentTimeMillis()
        return keys.firstOrNull { (COOLDOWN[it] ?: 0L) <= now }
            ?: keys.minByOrNull { COOLDOWN[it] ?: 0L } ?: ""
    }

    private fun hasFreshKey(): Boolean {
        val now = System.currentTimeMillis()
        return keys.any { (COOLDOWN[it] ?: 0L) <= now }
    }

    /** @param style ixtiyoriy yetkazish uslubi (masalan "speaking rapidly"); matnga aralashtirilmaydi */
    fun synthesize(text: String, voice: String, style: String? = null): VitsSynthesizer.SegmentAudio {
        if (keys.isEmpty()) throw TtsException("Gemini API kaliti kiritilmagan", fatal = true)
        val sleeps = longArrayOf(4_000, 12_000, 25_000, 45_000)
        var lastErr: TtsException? = null
        var attempt = 0
        var switches = 0
        while (attempt <= sleeps.size) {
            val key = pickKey()
            try {
                return call(key, text, voice, style)
            } catch (e: TtsException) {
                lastErr = e
                if (e.keyIssue && keys.size > 1) COOLDOWN[key] = System.currentTimeMillis() + restFor(e)
                if (e.keyIssue && keys.size > 1 && switches < keys.size && hasFreshKey()) {
                    switches++
                    Timber.w("Gemini TTS kaliti ishlamadi (HTTP ${e.code}), keyingisiga o'tilmoqda")
                    continue
                }
                if (e.fatal) throw e
                if (attempt < sleeps.size) Thread.sleep(sleeps[attempt])
                attempt++
            }
        }
        throw lastErr ?: TtsException("Noma'lum xato")
    }

    private fun restFor(e: TtsException): Long = if (e.code == 429) 65_000L else 12 * 3600_000L

    private fun call(key: String, text: String, voice: String, style: String?): VitsSynthesizer.SegmentAudio {
        val part = JSONObject().put("text", text)
        if (!style.isNullOrBlank()) part.put("speech_metadata", JSONObject().put("style", style))
        val body = JSONObject()
            .put("contents", org.json.JSONArray().put(
                JSONObject().put("role", "user").put("parts", org.json.JSONArray().put(part))
            ))
            .put("generationConfig", JSONObject()
                .put("responseModalities", org.json.JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject()
                    .put("voiceConfig", JSONObject().put("voice", voice))))

        val conn = URL("$BASE/models/$model:generateContent").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
                } catch (_: Exception) { raw.take(200) }
                val keyIssue = code == 429 || code == 401 || code == 403 || code == 402 ||
                    (code == 400 && msg.contains("api key", ignoreCase = true))
                val fatal = code == 400 || code == 401 || code == 402 || code == 403 || code == 404
                val hint = when (code) {
                    400 -> if (keyIssue) "Gemini kaliti noto'g'ri" else "So'rov noto'g'ri (ovoz/model nomini tekshiring)"
                    401, 403 -> "Gemini kaliti ruxsat bermadi"
                    404 -> "Model topilmadi ($model)"
                    429 -> "Gemini limiti tugadi (kuting yoki boshqa kalit qo'shing)"
                    else -> "Gemini xatosi"
                }
                throw TtsException("HTTP $code — $hint${if (msg.isNotEmpty()) ": ${msg.take(160)}" else ""}", code, fatal, keyIssue)
            }
            return parse(raw)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(raw: String): VitsSynthesizer.SegmentAudio {
        val root = JSONObject(raw)
        val cand = root.optJSONArray("candidates")?.optJSONObject(0)
        val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
        var data: String? = null
        var mime = ""
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val inl = parts.optJSONObject(i)?.optJSONObject("inlineData")
                    ?: parts.optJSONObject(i)?.optJSONObject("inline_data")
                if (inl != null && inl.optString("data").isNotEmpty()) {
                    data = inl.optString("data")
                    mime = inl.optString("mimeType", inl.optString("mime_type", ""))
                    break
                }
            }
        }
        if (data == null) {
            val reason = cand?.optString("finishReason").orEmpty()
            throw TtsException("Gemini ovoz qaytarmadi${if (reason.isNotEmpty()) " ($reason)" else ""}")
        }
        val bytes = Base64.getDecoder().decode(data)
        var sr = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24_000
        var offset = 0
        var end = bytes.size
        // WAV (RIFF) bo'lsa: sarlavhani o'tkazib, "data" bo'lagini olamiz
        if (bytes.size > 44 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
        ) {
            sr = le32(bytes, 24).takeIf { it in 8_000..96_000 } ?: sr
            var p = 12
            while (p + 8 <= bytes.size) {
                val id = String(bytes, p, 4, Charsets.US_ASCII)
                val size = le32(bytes, p + 4)
                if (id == "data") {
                    offset = p + 8
                    end = if (size > 0 && offset + size <= bytes.size) offset + size else bytes.size
                    break
                }
                p += 8 + size + (size and 1)
            }
            if (offset == 0) offset = 44
        }
        val n = (end - offset) / 2
        if (n <= 0) throw TtsException("Gemini bo'sh audio qaytardi")
        val pcm = ShortArray(n) { i ->
            val lo = bytes[offset + 2 * i].toInt() and 0xFF
            val hi = bytes[offset + 2 * i + 1].toInt()
            ((hi shl 8) or lo).toShort()
        }
        return VitsSynthesizer.SegmentAudio(trimSilence(pcm, sr), sr, 1f)
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    /** Boshi/oxiridagi jimlikni kesadi (vaqt hisobi aniqroq bo'lsin). */
    private fun trimSilence(a: ShortArray, sr: Int): ShortArray {
        if (a.size < sr / 10) return a
        var peak = 0
        for (v in a) { val x = if (v < 0) -v.toInt() else v.toInt(); if (x > peak) peak = x }
        if (peak < 300) return a
        val thr = maxOf(120, (peak * 0.012f).toInt())
        val win = maxOf(1, sr / 200)
        fun loud(start: Int): Boolean {
            val e = minOf(a.size, start + win)
            if (e <= start) return false
            var s = 0L
            for (i in start until e) s += if (a[i] < 0) -a[i].toInt() else a[i].toInt()
            return s / (e - start) > thr
        }
        var first = 0
        while (first < a.size && !loud(first)) first += win
        var last = a.size
        while (last > 0 && !loud(maxOf(0, last - win))) last -= win
        if (first >= last) return a
        val from = maxOf(0, first - sr * 20 / 1000)
        val to = minOf(a.size, last + sr * 50 / 1000)
        return a.copyOfRange(from, to)
    }

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

        /** Limiti tugagan kalitlar: kalit -> qachongacha ishlatilmaydi. */
        private val COOLDOWN = ConcurrentHashMap<String, Long>()
        fun resetCooldowns() = COOLDOWN.clear()

        /** Gemini'ga yuboriladigan matn: raqamlar so'zga aylantiriladi, tire pauza bo'ladi. */
        fun prepare(norm: TextNormalizer, text: String): String =
            norm.normalize(text).replace(Regex("\\s*[–—―]\\s*|\\s+-\\s+"), ", ").trim()
    }
}
