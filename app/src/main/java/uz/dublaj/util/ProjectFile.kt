package uz.dublaj.util

import org.json.JSONArray
import org.json.JSONObject
import uz.dublaj.data.AppConfig
import uz.dublaj.data.TranslationUnit

/**
 * Loyiha fayli (.dublaj): matn, tarjima, rollar, spikerlar va ovoz sozlamalari.
 * Video va API kalitlari faylga YOZILMAYDI (kalit maxfiy, video katta).
 * Boshqa telefonda: loyihani oching, keyin o'sha videoni tanlang.
 */
object ProjectFile {
    const val FORMAT = 1
    const val MIME = "application/octet-stream"

    class Data(
        val title: String,
        val units: List<TranslationUnit>,
        val settings: JSONObject
    )

    fun toJson(title: String, config: AppConfig, units: List<TranslationUnit>): String {
        val arr = JSONArray()
        for (u in units) {
            arr.put(JSONObject().apply {
                put("id", u.id); put("start", u.start); put("end", u.end)
                put("text", u.text); put("uzText", u.uzText)
                put("language", u.language); put("check", u.check)
                if (u.issue != null) put("issue", u.issue)
                put("voiceRole", u.voiceRole)
                if (u.speaker > 0) put("speaker", u.speaker)
                if (u.alts.isNotEmpty()) put("alts", JSONArray(u.alts))
            })
        }
        val s = JSONObject().apply {
            put("voiceNoise", config.voiceNoise.toDouble())
            put("voiceNoiseW", config.voiceNoiseW.toDouble())
            put("voiceSpeed", config.voiceSpeed.toDouble())
            put("voicePause", config.voicePause.toDouble())
            put("voicePitch", config.voicePitch.toDouble())
            put("fitScale", config.fitScale.toDouble())
            put("maxAtempo", config.maxAtempo.toDouble())
            put("duckAmount", config.duckAmount.toDouble())
            put("originalVolume", config.originalVolume.toDouble())
            put("uzbekVolume", config.uzbekVolume.toDouble())
            put("rolePitchMale", config.rolePitchMale.toDouble())
            put("rolePitchFemale", config.rolePitchFemale.toDouble())
            put("rolePitchChild", config.rolePitchChild.toDouble())
            put("baseVoiceFemale", config.baseVoiceFemale)
            put("sourceLang", config.sourceLang)
            put("videoContext", config.videoContext)
            put("addressForm", config.addressForm)
            put("tone", config.tone)
            put("glossary", config.glossary)
            put("pronDict", config.pronDict)
        }
        return JSONObject()
            .put("app", "DublajUz")
            .put("format", FORMAT)
            .put("title", title)
            .put("units", arr)
            .put("settings", s)
            .toString(2)
    }

    /** @throws IllegalArgumentException fayl DublajUz loyihasi bo'lmasa yoki buzuq bo'lsa */
    fun parse(text: String): Data {
        val root = try { JSONObject(text) } catch (e: Exception) {
            throw IllegalArgumentException("Bu DublajUz loyiha fayli emas")
        }
        if (root.optString("app") != "DublajUz") throw IllegalArgumentException("Bu DublajUz loyiha fayli emas")
        if (root.optInt("format", 0) > FORMAT) throw IllegalArgumentException("Loyiha yangiroq versiyada saqlangan. Ilovani yangilang")
        val arr = root.optJSONArray("units") ?: throw IllegalArgumentException("Loyihada gaplar yo'q")
        val units = try {
            (0 until arr.length()).map { i ->
                val u = arr.getJSONObject(i)
                TranslationUnit(
                    id = u.getInt("id"),
                    start = u.getDouble("start"),
                    end = u.getDouble("end"),
                    text = u.getString("text"),
                    uzText = u.getString("uzText"),
                    language = u.optString("language", "en"),
                    check = u.optBoolean("check", false),
                    alts = u.optJSONArray("alts")?.let { a -> (0 until a.length()).map { k -> a.getString(k) } } ?: emptyList(),
                    issue = if (u.has("issue") && !u.isNull("issue")) u.getString("issue") else null,
                    voiceRole = u.optString("voiceRole", ""),
                    hasUserVoice = false,            // yozilgan ovozlar faylga kirmaydi
                    speaker = u.optInt("speaker", 0)
                )
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Loyiha fayli buzilgan: ${e.message}")
        }
        if (units.isEmpty()) throw IllegalArgumentException("Loyihada gaplar yo'q")
        return Data(root.optString("title", ""), units, root.optJSONObject("settings") ?: JSONObject())
    }

    /** Fayldagi ovoz/aralashtirish/tarjima uslubi sozlamalarini qo'llaydi (kalitlar va boshqalar o'zgarmaydi). */
    fun applySettings(c: AppConfig, s: JSONObject): AppConfig {
        fun f(k: String, d: Float) = if (s.has(k)) s.optDouble(k, d.toDouble()).toFloat() else d
        fun str(k: String, d: String) = if (s.has(k)) s.optString(k, d) else d
        return c.copy(
            voiceNoise = f("voiceNoise", c.voiceNoise),
            voiceNoiseW = f("voiceNoiseW", c.voiceNoiseW),
            voiceSpeed = f("voiceSpeed", c.voiceSpeed),
            voicePause = f("voicePause", c.voicePause),
            voicePitch = f("voicePitch", c.voicePitch),
            fitScale = f("fitScale", c.fitScale),
            maxAtempo = f("maxAtempo", c.maxAtempo),
            duckAmount = f("duckAmount", c.duckAmount),
            originalVolume = f("originalVolume", c.originalVolume),
            uzbekVolume = f("uzbekVolume", c.uzbekVolume),
            rolePitchMale = f("rolePitchMale", c.rolePitchMale),
            rolePitchFemale = f("rolePitchFemale", c.rolePitchFemale),
            rolePitchChild = f("rolePitchChild", c.rolePitchChild),
            baseVoiceFemale = if (s.has("baseVoiceFemale")) s.optBoolean("baseVoiceFemale", c.baseVoiceFemale) else c.baseVoiceFemale,
            sourceLang = str("sourceLang", c.sourceLang),
            videoContext = str("videoContext", c.videoContext),
            addressForm = str("addressForm", c.addressForm),
            tone = str("tone", c.tone),
            glossary = str("glossary", c.glossary),
            pronDict = str("pronDict", c.pronDict)
        )
    }
}
