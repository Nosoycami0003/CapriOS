package com.endrunners.capri

enum class AIProvider(
    val displayName: String,
    val supportsVision: Boolean,
    val defaultModel: String
) {
    CLAUDE("Anthropic Claude", true, "claude-3-5-sonnet-20241022"),
    OPENAI("OpenAI GPT", true, "gpt-4o-mini"),
    GEMINI("Google Gemini", true, "gemini-1.5-flash"),
    MISTRAL("Mistral (solo texto, no ve pantalla)", false, "mistral-small-latest");

    companion object {
        fun fromId(id: String?): AIProvider =
            entries.firstOrNull { it.name == id } ?: CLAUDE
    }
}
