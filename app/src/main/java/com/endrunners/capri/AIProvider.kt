package com.endrunners.capri

enum class AIProvider(
    val displayName: String,
    val supportsVision: Boolean,
    val defaultModel: String
) {
    CLAUDE("Anthropic Claude (ve pantalla)", true, "claude-haiku-5-5"),
    OPENAI("OpenAI GPT (ve pantalla)", true, "gpt-4o-mini"),
    GEMINI("Google Gemini (ve pantalla)", true, "gemini-2.5-flash"),
    MISTRAL("Mistral (solo texto, no ve pantalla)", false, "mistral-small-latest");

    companion object {
        fun fromId(id: String?): AIProvider =
            entries.firstOrNull { it.name == id } ?: CLAUDE
    }
}
