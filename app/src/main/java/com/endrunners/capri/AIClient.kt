package com.endrunners.capri

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object AIClient {

    private val executor = Executors.newSingleThreadExecutor()

    fun ask(
        context: Context,
        prompt: String,
        capturarPantalla: () -> String?,
        onResult: (String) -> Unit
    ) {
        executor.execute {
            val respuesta = try {
                val provider = CapriPrefs.getProvider(context)
                val apiKey = CapriPrefs.getApiKey(context)
                val model = CapriPrefs.getModel(context)

                if (apiKey.isBlank()) {
                    "No tenés ninguna clave de API configurada. Abrí Configurar IA y pegá la tuya."
                } else {
                    val imagen = if (provider.supportsVision) capturarPantalla() else null
                    when (provider) {
                        AIProvider.CLAUDE -> callClaude(apiKey, model, prompt, imagen)
                        AIProvider.OPENAI -> callOpenAI(apiKey, model, prompt, imagen)
                        AIProvider.GEMINI -> callGemini(apiKey, model, prompt, imagen)
                        AIProvider.MISTRAL -> callMistral(apiKey, model, prompt)
                    }
                }
            } catch (e: Exception) {
                "Hubo un error hablando con la IA: ${e.message}"
            }
            onResult(respuesta)
        }
    }

    private fun postJson(urlStr: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.connectTimeout = 20000
        conn.readTimeout = 30000

        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val texto = stream.bufferedReader().use { it.readText() }

        if (code !in 200..299) {
            throw RuntimeException("HTTP $code: $texto")
        }
        return JSONObject(texto)
    }

    private fun callClaude(apiKey: String, model: String, prompt: String, imageBase64: String?): String {
        val contentArray = JSONArray()
        if (imageBase64 != null) {
            contentArray.put(JSONObject().apply {
                put("type", "image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", "image/jpeg")
                    put("data", imageBase64)
                })
            })
        }
        contentArray.put(JSONObject().apply {
            put("type", "text")
            put("text", prompt)
        })

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 300)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", contentArray)
            }))
        }

        val res = postJson(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
            body
        )
        return res.getJSONArray("content").getJSONObject(0).getString("text")
    }

    private fun callOpenAI(apiKey: String, model: String, prompt: String, imageBase64: String?): String {
        val contentArray = JSONArray()
        contentArray.put(JSONObject().apply {
            put("type", "text")
            put("text", prompt)
        })
        if (imageBase64 != null) {
            contentArray.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$imageBase64")
                })
            })
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", contentArray)
            }))
            put("max_tokens", 300)
        }

        val res = postJson(
            "https://api.openai.com/v1/chat/completions",
            mapOf("Authorization" to "Bearer $apiKey"),
            body
        )
        return res.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    }

    private fun callGemini(apiKey: String, model: String, prompt: String, imageBase64: String?): String {
        val parts = JSONArray()
        parts.put(JSONObject().apply { put("text", prompt) })
        if (imageBase64 != null) {
            parts.put(JSONObject().apply {
                put("inline_data", JSONObject().apply {
                    put("mime_type", "image/jpeg")
                    put("data", imageBase64)
                })
            })
        }

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", parts)
            }))
        }

        val res = postJson(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey",
            mapOf(),
            body
        )
        return res.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
            .getString("text")
    }

    private fun callMistral(apiKey: String, model: String, prompt: String): String {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            }))
        }

        val res = postJson(
            "https://api.mistral.ai/v1/chat/completions",
            mapOf("Authorization" to "Bearer $apiKey"),
            body
        )
        return res.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    }
}
