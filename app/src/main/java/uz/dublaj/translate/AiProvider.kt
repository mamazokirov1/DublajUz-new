package uz.dublaj.translate

import uz.dublaj.data.AppConfig

/** So'rov formati: har provayder shu uchtadan birini ishlatadi. */
enum class WireFormat { GEMINI, OPENAI, ANTHROPIC }

/**
 * Tarjima uchun AI provayderlar. Har biri uchun: standart model, tavsiya etilgan modellar,
 * kalit olish havolasi va qadamma-qadam ko'rsatma. Model nomlari tez eskiradi,
 * shuning uchun foydalanuvchi modelni qo'lda yozishi yoki "Modellarni olish" tugmasi bilan tanlashi mumkin.
 */
enum class AiProvider(
    val id: String,
    val label: String,
    val wire: WireFormat,
    val baseUrl: String,
    val keyUrl: String,
    val defaultModel: String,
    val suggested: List<String>,
    val pricing: String,
    val steps: List<String>,
    val customUrl: Boolean = false
) {
    GEMINI(
        "gemini", "Google Gemini", WireFormat.GEMINI,
        "https://generativelanguage.googleapis.com", "https://aistudio.google.com/apikey",
        "gemini-3.5-flash",
        listOf("gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.5-pro"),
        "Bepul daraja bor (kunlik limit bilan). Boshlovchilar uchun eng qulayi.",
        listOf(
            "Pastdagi \"Kalit olish\" tugmasini bosing (Google AI Studio ochiladi).",
            "Google akkauntingiz bilan kiring.",
            "\"Create API key\" tugmasini bosing.",
            "Chiqqan kalitni (AIza… bilan boshlanadi) nusxalab, kalit maydoniga qo'ying."
        )
    ),
    OPENAI(
        "openai", "OpenAI (ChatGPT)", WireFormat.OPENAI,
        "https://api.openai.com/v1", "https://platform.openai.com/api-keys",
        "gpt-5-mini",
        listOf("gpt-5-mini", "gpt-5-nano", "gpt-5.4-mini", "gpt-5.4-nano", "gpt-4.1-mini"),
        "Pullik: hisobga oldindan pul (kredit) solish kerak. ChatGPT Plus obunasi API uchun ishlamaydi.",
        listOf(
            "platform.openai.com saytiga kiring (ChatGPT akkaunti bilan ham bo'ladi).",
            "Billing bo'limida hisobingizga kamida \$5 kredit soling.",
            "\"Kalit olish\" tugmasini bosing, \"Create new secret key\" ni bosing.",
            "Kalitni (sk-… bilan boshlanadi) darhol nusxalang — u keyin qayta ko'rsatilmaydi.",
            "Kalitni maydonga qo'ying va \"Tekshirish\" ni bosing."
        )
    ),
    ANTHROPIC(
        "anthropic", "Anthropic (Claude)", WireFormat.ANTHROPIC,
        "https://api.anthropic.com", "https://console.anthropic.com/settings/keys",
        "claude-haiku-5-5",
        listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5"),
        "Pullik: kredit solish kerak. Tarjima sifati yuqori.",
        listOf(
            "console.anthropic.com saytida ro'yxatdan o'ting.",
            "Plans & Billing bo'limida kredit soling.",
            "\"Kalit olish\" tugmasini bosing, \"Create Key\" ni bosing.",
            "Kalitni (sk-ant-… bilan boshlanadi) nusxalab maydonga qo'ying."
        )
    ),
    DEEPSEEK(
        "deepseek", "DeepSeek", WireFormat.OPENAI,
        "https://api.deepseek.com/v1", "https://platform.deepseek.com/api_keys",
        "deepseek-chat",
        listOf("deepseek-chat", "deepseek-reasoner"),
        "Juda arzon (pullik, kichik kredit yetarli).",
        listOf(
            "platform.deepseek.com saytida ro'yxatdan o'ting.",
            "Hisobni to'ldiring (kichik summa yetadi).",
            "\"Kalit olish\" tugmasini bosib, \"Create new API key\" ni bosing.",
            "Kalitni (sk-… bilan boshlanadi) maydonga qo'ying."
        )
    ),
    GROQ(
        "groq", "Groq", WireFormat.OPENAI,
        "https://api.groq.com/openai/v1", "https://console.groq.com/keys",
        "llama-3.3-70b-versatile",
        listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant"),
        "Bepul daraja bor, juda tez. O'zbekcha sifati o'rtacharoq bo'lishi mumkin.",
        listOf(
            "console.groq.com saytida Google yoki email bilan kiring.",
            "\"Kalit olish\" tugmasini bosib, \"Create API Key\" ni bosing.",
            "Kalitni (gsk_… bilan boshlanadi) maydonga qo'ying."
        )
    ),
    OPENROUTER(
        "openrouter", "OpenRouter", WireFormat.OPENAI,
        "https://openrouter.ai/api/v1", "https://openrouter.ai/keys",
        "openrouter/auto",
        listOf("openrouter/auto", "google/gemini-3.5-flash", "deepseek/deepseek-chat"),
        "Bitta kalit bilan yuzlab model. Ba'zi modellar bepul (nomi :free bilan tugaydi).",
        listOf(
            "openrouter.ai saytida ro'yxatdan o'ting.",
            "\"Kalit olish\" tugmasini bosib, \"Create Key\" ni bosing.",
            "Kalitni (sk-or-… bilan boshlanadi) maydonga qo'ying.",
            "Model nomini openrouter.ai/models sahifasidan olib yozishingiz mumkin."
        )
    ),
    MISTRAL(
        "mistral", "Mistral", WireFormat.OPENAI,
        "https://api.mistral.ai/v1", "https://console.mistral.ai/api-keys",
        "mistral-small-latest",
        listOf("mistral-small-latest", "mistral-large-latest"),
        "Bepul sinov darajasi bor.",
        listOf(
            "console.mistral.ai saytida ro'yxatdan o'ting.",
            "\"Kalit olish\" tugmasini bosib, \"Create new key\" ni bosing.",
            "Kalitni nusxalab maydonga qo'ying."
        )
    ),
    CUSTOM(
        "custom", "Boshqa (OpenAI-mos)", WireFormat.OPENAI,
        "", "https://platform.openai.com/docs/api-reference/chat",
        "",
        emptyList(),
        "Istalgan OpenAI-mos xizmat: Qwen, Kimi, Together, o'z serveringiz (Ollama, LM Studio) va hokazo.",
        listOf(
            "Xizmatning \"Base URL\" manzilini toping (odatda /v1 bilan tugaydi).",
            "Manzilni, kalitni va model nomini quyidagi maydonlarga kiriting.",
            "Lokal server (Ollama) uchun kalit ixtiyoriy: istalgan matn yozing."
        ),
        customUrl = true
    );

    companion object {
        fun byId(id: String): AiProvider = values().firstOrNull { it.id == id } ?: GEMINI
    }
}

/** Faol provayder, kalit va model — AppConfig dan. */
fun AppConfig.provider(): AiProvider = AiProvider.byId(aiProvider)

fun AppConfig.activeKey(): String =
    if (provider() == AiProvider.GEMINI) geminiKey else aiKeys[aiProvider].orEmpty()

fun AppConfig.activeModel(): String {
    val p = provider()
    val m = if (p == AiProvider.GEMINI) geminiModel else aiModels[aiProvider].orEmpty()
    return m.trim().ifBlank { p.defaultModel }
}

fun AppConfig.activeBaseUrl(): String =
    if (provider().customUrl) aiBaseUrl.trim().trimEnd('/') else provider().baseUrl

fun AppConfig.newTranslator(): GeminiTranslator =
    GeminiTranslator(activeKey(), activeModel(), provider(), activeBaseUrl())
