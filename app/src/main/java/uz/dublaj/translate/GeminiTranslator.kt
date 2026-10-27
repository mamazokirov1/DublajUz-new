package uz.dublaj.translate

import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google Gemini orqali kontekstli tarjima (internet kerak). Gemini tarjima:
 * jargon, og'zaki nutq va ASR xatolarini kontekstga qarab tushunadi.
 * Faqat HttpURLConnection + org.json ishlatiladi (qo'shimcha kutubxona kerak emas).
 */
class GeminiTranslator(
    private val apiKey: String,
    private val model: String,
    private val provider: AiProvider = AiProvider.GEMINI,
    private val baseUrl: String = AiProvider.GEMINI.baseUrl
) {
    data class Item(val id: Int, val text: String, val maxChars: Int)
    data class Result(val uz: String, val short: String?)

    /** Foydalanuvchi bergan uslub ko'rsatmalari. */
    data class Style(
        val context: String = "",
        val address: String = "auto",   // auto | sen | siz
        val tone: String = "casual",    // casual | neutral | formal
        val glossary: String = "",      // har qatorda "so'z = tarjima"
        val extraPrompt: String = "",   // mutaxassis qo'shimcha ko'rsatmasi
        val temperature: Float = 0.3f
    )

    /** [fatal] = true: kalit noto'g'ri / ruxsat yo'q — qayta urinishdan foyda yo'q. */
    class GeminiException(message: String, val fatal: Boolean) : Exception(message)

    private fun endpoint(): String = when (provider.wire) {
        WireFormat.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/models/${model.trim()}:generateContent"
        WireFormat.OPENAI -> baseUrl.trimEnd('/') + "/chat/completions"
        WireFormat.ANTHROPIC -> "https://api.anthropic.com/v1/messages"
    }

    /** Provayderdan mavjud modellar ro'yxati. */
    fun listModels(): List<String> {
        val url = when (provider.wire) {
            WireFormat.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200"
            WireFormat.OPENAI -> baseUrl.trimEnd('/') + "/models"
            WireFormat.ANTHROPIC -> "https://api.anthropic.com/v1/models?limit=100"
        }
        val conn = try { URL(url).openConnection() as HttpURLConnection }
        catch (e: Exception) { throw GeminiException("Manzil noto'g'ri: ${e.message}", true) }
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            applyAuth(conn)
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw GeminiException("HTTP $code ${errorMessage(text)}".trim(), true)
            val root = JSONObject(text)
            val out = ArrayList<String>()
            if (provider.wire == WireFormat.GEMINI) {
                val arr = root.optJSONArray("models") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val methods = o.optJSONArray("supportedGenerationMethods")
                    var ok = methods == null
                    if (methods != null) for (k in 0 until methods.length()) if (methods.optString(k) == "generateContent") ok = true
                    val name = o.optString("name").removePrefix("models/")
                    if (ok && name.startsWith("gemini")) out.add(name)
                }
            } else {
                val arr = root.optJSONArray("data") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val id = arr.optJSONObject(i)?.optString("id").orEmpty()
                    if (id.isNotBlank()) out.add(id)
                }
            }
            return out.distinct().sorted()
        } catch (e: GeminiException) {
            throw e
        } catch (e: java.io.IOException) {
            throw GeminiException("Internet aloqasi: ${e.message}", false)
        } finally {
            conn.disconnect()
        }
    }

    private fun applyAuth(conn: HttpURLConnection) {
        when (provider.wire) {
            WireFormat.GEMINI -> conn.setRequestProperty("x-goog-api-key", apiKey.trim())
            WireFormat.OPENAI -> conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim())
            WireFormat.ANTHROPIC -> {
                conn.setRequestProperty("x-api-key", apiKey.trim())
                conn.setRequestProperty("anthropic-version", "2023-06-01")
            }
        }
    }

    private fun errorMessage(text: String): String = try {
        val o = JSONObject(text)
        val e = o.opt("error")
        when (e) {
            is JSONObject -> e.optString("message", "")
            is String -> e
            else -> o.optString("message", "")
        }.take(200)
    } catch (ex: Exception) { "" }

    /** Provayderga mos so'rov tanasi. */
    private fun buildBody(system: String, user: String, temperature: Float?, jsonMode: Boolean): JSONObject = when (provider.wire) {
        WireFormat.GEMINI -> JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", user)))))
            .put("generationConfig", JSONObject().apply {
                if (temperature != null) put("temperature", temperature.toDouble())
                if (jsonMode) put("responseMimeType", "application/json")
            })
        WireFormat.OPENAI -> JSONObject()
            .put("model", model.trim())
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            .apply {
                // OpenAI'ning yangi (GPT-5) modellari standartdan boshqa temperature'ni qabul qilmaydi
                if (temperature != null && provider != AiProvider.OPENAI) put("temperature", temperature.toDouble())
            }
        WireFormat.ANTHROPIC -> JSONObject()
            .put("model", model.trim())
            .put("max_tokens", 4096)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
    }

    /** Sozlamalardagi "Kalitni tekshirish" tugmasi uchun. */
    fun test(): String = try {
        val body = buildBody("You are a connection test.", "Reply with one word: OK", null, false)
        val text = extractText(post(body, retries = 1))
        if (text.isNotBlank()) "${provider.label} ishlayapti ✓ (${model.trim()})" else "${provider.label} javob berdi, lekin matn bo'sh"
    } catch (e: GeminiException) {
        "${provider.label} xato: ${e.message}"
    } catch (e: Exception) {
        "${provider.label} bilan aloqa yo'q: ${e.message}"
    }

    /**
     * Bir nechta ketma-ket gapni tarjima qiladi.
     * @param context oldingi gaplar (manba, tarjima) — faqat ma'no uchun
     * @param nextHint keyingi gapning manba matni (ixtiyoriy)
     */
    fun translateChunk(
        srcLang: String,
        context: List<Pair<String, String>>,
        items: List<Item>,
        nextHint: String?,
        style: Style = Style()
    ): Map<Int, Result> {
        if (items.isEmpty()) return emptyMap()

        val user = StringBuilder()
        user.append("Source language: ").append(langName(srcLang)).append("\n\n")
        if (context.isNotEmpty()) {
            user.append("Previously translated segments (context only, do NOT output them):\n")
            for ((s, t) in context) user.append("- SRC: ").append(s).append("\n  UZ: ").append(t).append('\n')
            user.append('\n')
        }
        val arr = JSONArray()
        for (item in items) {
            arr.put(JSONObject().put("id", item.id).put("maxChars", item.maxChars).put("text", item.text))
        }
        user.append("Segments to translate:\n").append(arr.toString()).append('\n')
        if (!nextHint.isNullOrBlank()) {
            user.append("\nNext segment after these (context only, do NOT output): ").append(nextHint).append('\n')
        }

        val body = buildBody(SYSTEM_PROMPT + styleBlock(style), user.toString(), style.temperature, true)

        val text = extractText(post(body, retries = 3))
        return parse(text)
    }

    // ------------------------------------------------------------------ ichki

    private fun styleBlock(s: Style): String {
        val sb = StringBuilder()
        sb.append("\n\nVideo-specific instructions (from the user, follow them):\n")
        val ctx = s.context.trim().take(600)
        if (ctx.isNotEmpty()) sb.append("- Video topic and style: ").append(ctx).append('\n')
        when (s.address) {
            "sen" -> sb.append("- Address the listener informally with \"sen\" (second person singular) consistently.\n")
            "siz" -> sb.append("- Address the listener politely with \"siz\" consistently.\n")
            else -> sb.append("- Choose sen/siz from the speaker's tone and keep it consistent.\n")
        }
        when (s.tone) {
            "formal" -> sb.append("- Register: polite, formal, clear Uzbek.\n")
            "neutral" -> sb.append("- Register: neutral, clear spoken Uzbek.\n")
            else -> sb.append(
                "- Register: casual, lively spoken Uzbek, the way a native friend or blogger would say it. " +
                    "Avoid bookish, literary or stiff words and avoid word-for-word calques of the source.\n"
            )
        }
        val terms = s.glossary.lines().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s*(=>|->|=)\\s*"), limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) parts[0].trim() to parts[1].trim() else null
        }.take(60)
        if (terms.isNotEmpty()) {
            sb.append("- Mandatory glossary (always render these terms exactly like this):\n")
            for ((a, b) in terms) sb.append("    ").append(a.take(80)).append(" => ").append(b.take(80)).append('\n')
        }
        val extra = s.extraPrompt.trim().take(1200)
        if (extra.isNotEmpty()) sb.append("- Additional instructions from the user: ").append(extra).append('\n')
        return sb.toString()
    }

    private fun parse(raw: String): Map<Int, Result> {
        var t = raw.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
            t = t.removeSuffix("```").trim()
        }
        if (!t.startsWith("[") && !t.startsWith("{")) {
            val a = t.indexOf('[')
            val b = t.lastIndexOf(']')
            if (a >= 0 && b > a) t = t.substring(a, b + 1)
        }
        val arr: JSONArray = try {
            if (t.startsWith("[")) JSONArray(t) else {
                val o = JSONObject(t)
                var found: JSONArray? = null
                val keys = o.keys()
                while (keys.hasNext() && found == null) found = o.optJSONArray(keys.next())
                found ?: throw GeminiException("Javob formati noto'g'ri", false)
            }
        } catch (e: GeminiException) {
            throw e
        } catch (e: Exception) {
            throw GeminiException("Javobni o'qib bo'lmadi (${e.message})", false)
        }
        val out = HashMap<Int, Result>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.has("id")) continue
            val id = o.optInt("id", Int.MIN_VALUE)
            val uz = o.optString("uz", "").trim()
            if (id == Int.MIN_VALUE || uz.isEmpty()) continue
            val short = o.optString("short", "").trim().ifEmpty { null }
            out[id] = Result(uz, short)
        }
        return out
    }

    private fun extractText(resp: JSONObject): String = when (provider.wire) {
        WireFormat.GEMINI -> extractGemini(resp)
        WireFormat.OPENAI -> {
            val msg = resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            val c = msg?.opt("content")
            if (c is JSONArray) {
                val sb = StringBuilder()
                for (i in 0 until c.length()) sb.append(c.optJSONObject(i)?.optString("text", "").orEmpty())
                sb.toString()
            } else msg?.optString("content", "").orEmpty()
        }
        WireFormat.ANTHROPIC -> {
            val arr = resp.optJSONArray("content") ?: JSONArray()
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type") == "text") sb.append(o.optString("text", ""))
            }
            sb.toString()
        }
    }

    private fun extractGemini(resp: JSONObject): String {
        val block = resp.optJSONObject("promptFeedback")?.optString("blockReason", "") ?: ""
        if (block.isNotEmpty()) throw GeminiException("So'rov bloklandi: $block", false)
        val cands = resp.optJSONArray("candidates") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until cands.length()) {
            val parts = cands.optJSONObject(i)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val p = parts.optJSONObject(j) ?: continue
                if (p.optBoolean("thought", false)) continue
                sb.append(p.optString("text", ""))
            }
            if (sb.isNotEmpty()) break
        }
        return sb.toString()
    }

    private fun post(body: JSONObject, retries: Int): JSONObject {
        var lastErr: GeminiException? = null
        val delays = longArrayOf(2_000, 5_000, 12_000)
        for (attempt in 0 until retries) {
            try {
                return postOnce(body)
            } catch (e: GeminiException) {
                if (e.fatal) throw e
                lastErr = e
                Timber.w("Gemini urinish ${attempt + 1}/$retries: ${e.message}")
                if (attempt < retries - 1) Thread.sleep(delays[min2(attempt, delays.size - 1)])
            }
        }
        throw lastErr ?: GeminiException("Noma'lum xato", false)
    }

    private fun min2(a: Int, b: Int) = if (a < b) a else b

    private fun postOnce(body: JSONObject): JSONObject {
        val conn = try {
            URL(endpoint()).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw GeminiException("Manzil noto'g'ri: ${e.message}", true)
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            applyAuth(conn)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code in 200..299) {
                return try {
                    JSONObject(text)
                } catch (e: Exception) {
                    throw GeminiException("Javob JSON emas", false)
                }
            }
            val msg = errorMessage(text)
            val fatal = code == 400 || code == 401 || code == 402 || code == 403 || code == 404
            val hint = when (code) {
                400 -> "so'rov yoki kalit noto'g'ri"
                401, 403 -> "kalit noto'g'ri yoki ruxsat yo'q"
                402 -> "hisobda mablag' yo'q (balansni to'ldiring)"
                404 -> "model topilmadi (model nomini tekshiring)"
                429 -> "limit tugadi, biroz kutish kerak"
                else -> "server xatosi"
            }
            throw GeminiException("HTTP $code — $hint${if (msg.isNotEmpty()) ": $msg" else ""}", fatal)
        } catch (e: GeminiException) {
            throw e
        } catch (e: java.io.IOException) {
            throw GeminiException("Internet aloqasi: ${e.message}", false)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-flash"
        const val KEY_URL = "https://aistudio.google.com/apikey"

        /** Taxminiy TTS tezligi: soniyasiga shuncha belgi (uzunlik cheklovi uchun). */
        const val CHARS_PER_SEC = 16

        fun langName(code: String): String = when (code.lowercase()) {
            "en" -> "English"; "ru" -> "Russian"; "tr" -> "Turkish"; "ar" -> "Arabic"
            "de" -> "German"; "fr" -> "French"; "es" -> "Spanish"; "it" -> "Italian"
            "pt" -> "Portuguese"; "zh" -> "Chinese"; "ja" -> "Japanese"; "ko" -> "Korean"
            "hi" -> "Hindi"; "fa" -> "Persian"; "uk" -> "Ukrainian"; "kk" -> "Kazakh"
            "tg" -> "Tajik"; "pl" -> "Polish"; "nl" -> "Dutch"; "id" -> "Indonesian"
            "vi" -> "Vietnamese"; "th" -> "Thai"; "az" -> "Azerbaijani"
            else -> "language code \"$code\""
        }

        private val SYSTEM_PROMPT = """
You are an expert audiovisual translator who dubs videos into Uzbek (Latin script, modern standard Uzbek as spoken in Uzbekistan).
Translate every numbered segment into natural SPOKEN Uzbek that sounds like a native voice-over.

Rules:
1. Meaning first. Be faithful: never invent, omit or soften content.
2. The segments come from automatic speech recognition. If a word is obviously misrecognized, infer the intended word from the context.
3. Idioms, slang, jokes and colloquial speech: first work out what the speaker really means, then say it the way a native Uzbek speaker would in the same situation. Never translate word for word. Prefer everyday Uzbek words; keep a Russian/English loanword only when Uzbeks really use it (mashina, telefon, internet).
4. Keep proper names, brands and places; use standard Uzbek Latin spelling where one exists.
5. Write numbers as digits.
6. Use the plain ASCII apostrophe for o', g' and a'lo (never typographic quotes).
7. Each segment has "maxChars". Keep "uz" at or below it by choosing concise phrasing, but never at the cost of changing the meaning.
8. Also give "short": a more concise version of the same translation (about 75% of the length) that keeps the key meaning. It is used only when the audio does not fit.
9. Segments are consecutive parts of one video: keep terminology, tone and speaker address (sen/siz) consistent. Context lines are for reference only.
10. No explanations, notes or extra quotation marks.

Output ONLY a JSON array with exactly one object per segment to translate:
[{"id": <int>, "uz": "<translation>", "short": "<concise translation>"}]
""".trimIndent()
    }
}
