package app.onion.generation

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

interface LocalLlmClient {
    suspend fun generateTitle(prompt: String): String
    suspend fun generateHtml(prompt: String): String
}

class MediaPipeLocalLlmClient(
    context: Context,
) : LocalLlmClient {
    private val appContext = context.applicationContext

    override suspend fun generateTitle(prompt: String): String {
        return generate(prompt, maxTokens = 64)
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.removeSurrounding("\"")
            ?.take(32)
            ?: "새 앱"
    }

    override suspend fun generateHtml(prompt: String): String {
        return extractHtml(generate(prompt, maxTokens = 4096))
    }

    private suspend fun generate(
        prompt: String,
        maxTokens: Int,
    ): String = withContext(Dispatchers.IO) {
        val modelFile = findModelFile()
            ?: error("앱 생성 도구 모델 파일이 없습니다. 설정에서 앱 생성 도구를 먼저 받아주세요.")

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(maxTokens)
            .setMaxTopK(64)
            .build()

        LlmInference.createFromOptions(appContext, options).use { inference ->
            inference.generateResponse(prompt)
        }
    }

    private fun findModelFile(): File? {
        val modelDir = File(appContext.filesDir, "models/app_creation_tool")
        return listOf(
            File(modelDir, "model.task"),
            File(modelDir, "model.litertlm"),
        ).firstOrNull { it.exists() && it.length() > 0L }
    }

    private fun extractHtml(raw: String): String {
        val fenced = Regex("```(?:html)?\\s*([\\s\\S]*?)```").find(raw)?.groupValues?.getOrNull(1)
        val candidate = fenced ?: raw
        val start = candidate.indexOf("<!doctype", ignoreCase = true).takeIf { it >= 0 }
            ?: candidate.indexOf("<html", ignoreCase = true).takeIf { it >= 0 }
            ?: 0
        val endHtml = candidate.indexOf("</html>", start, ignoreCase = true)
        return if (endHtml >= 0) {
            candidate.substring(start, endHtml + "</html>".length).trim()
        } else {
            candidate.substring(start).trim()
        }
    }
}

class LocalLlmNotConnectedClient : LocalLlmClient {
    override suspend fun generateTitle(prompt: String): String {
        return prompt
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.replace(Regex("[^가-힣A-Za-z0-9\\s]"), " ")
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            ?.take(4)
            ?.joinToString(" ")
            ?.take(24)
            ?.ifBlank { "새 앱" }
            ?: "새 앱"
    }

    override suspend fun generateHtml(prompt: String): String {
        return localLlmMissingHtml(prompt)
    }

    private fun localLlmMissingHtml(prompt: String): String {
        val safePrompt = prompt.escapeHtml()
        return """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <style>
                * { box-sizing: border-box; }
                body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background: #f7f8f1; color: #101510; }
                main { min-height: 100vh; display: grid; place-items: center; padding: 24px; }
                section { width: 100%; max-width: 420px; background: white; border: 1px solid #dfe7d8; border-radius: 22px; padding: 20px; box-shadow: 0 12px 32px rgba(16,21,16,.08); }
                h1 { margin: 0 0 10px; font-size: 24px; line-height: 1.1; }
                p { color: #5d6559; line-height: 1.5; }
                pre { white-space: pre-wrap; word-break: break-word; background: #f1f5eb; border-radius: 14px; padding: 14px; color: #101510; }
              </style>
            </head>
            <body>
              <main>
                <section>
                  <h1>앱 생성 도구 연결 필요</h1>
                  <p>요청은 생성 엔진으로 전달될 준비가 되었지만, 아직 로컬 LLM 런타임이 연결되지 않았습니다.</p>
                  <pre>$safePrompt</pre>
                </section>
              </main>
            </body>
            </html>
        """.trimIndent()
    }

    private fun String.escapeHtml(): String {
        return replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
