package app.onion.creation

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AppCreationToolManager(
    context: Context,
    recommendedModel: AppCreationToolModel,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("onion", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelDirectory = File(appContext.filesDir, "models/app_creation_tool")
    private val modelFile = File(modelDirectory, "model.litertlm")
    private val tempModelFile = File(modelDirectory, "model.litertlm.download")
    private val selectedModelKey = "creation_tool_selected_model"
    private val installedModelKey = "creation_tool_installed_model"
    private val downloadInProgressKey = "creation_tool_download_in_progress"
    private val downloadStartedAtKey = "creation_tool_download_started_at"
    private val staleDownloadAfterMs = 12 * 60 * 60 * 1000L

    private val storedSelectedModel = preferences.getString(selectedModelKey, null)
        ?.let { stored -> AppCreationToolModel.entries.firstOrNull { it.name == stored } }

    private val _state = MutableStateFlow(
        AppCreationToolState(
            selectedModel = storedSelectedModel ?: recommendedModel,
            recommendedModel = recommendedModel,
            downloadStatus = initialStatus(storedSelectedModel ?: recommendedModel),
            progress = when {
                isInstalled(storedSelectedModel ?: recommendedModel) -> 1f
                isDownloadMarkedActive() -> 0.01f
                else -> 0f
            },
            statusMessage = if (isDownloadMarkedActive()) {
                "앱 생성 도구를 백그라운드에서 받는 중입니다."
            } else {
                null
            },
        ),
    )
    val state: StateFlow<AppCreationToolState> = _state.asStateFlow()

    fun selectModel(model: AppCreationToolModel) {
        if (_state.value.downloadStatus == DownloadStatus.Downloading || isDownloadMarkedActive()) return
        preferences.edit().putString(selectedModelKey, model.name).apply()
        val installed = isInstalled(model)
        _state.update {
            it.copy(
                selectedModel = model,
                downloadStatus = if (installed) DownloadStatus.Ready else DownloadStatus.NotDownloaded,
                progress = if (installed) 1f else 0f,
                downloadedBytes = if (installed) modelFile.length() else 0L,
                totalBytes = if (installed) modelFile.length() else 0L,
                statusMessage = null,
            )
        }
    }

    fun startDownloadInBackground() {
        if (_state.value.downloadStatus == DownloadStatus.Downloading || isDownloadMarkedActive()) {
            _state.update {
                it.copy(
                    downloadStatus = DownloadStatus.Downloading,
                    statusMessage = "이미 앱 생성 도구를 받는 중입니다.",
                )
            }
            return
        }
        val model = _state.value.selectedModel
        markDownloadActive()

        _state.update {
            it.copy(
                downloadStatus = DownloadStatus.Downloading,
                progress = 0.01f,
                downloadedBytes = 0L,
                totalBytes = 0L,
                statusMessage = "Hugging Face에서 앱 생성 도구를 받는 중입니다.",
            )
        }

        scope.launch {
            runCatching {
                downloadModel(model)
            }.onSuccess {
                preferences.edit()
                    .putString(selectedModelKey, model.name)
                    .putString(installedModelKey, model.name)
                    .putBoolean(downloadInProgressKey, false)
                    .apply()
                _state.update {
                    it.copy(
                        selectedModel = model,
                        downloadStatus = DownloadStatus.Ready,
                        progress = 1f,
                        downloadedBytes = modelFile.length(),
                        totalBytes = modelFile.length(),
                        statusMessage = "앱 생성 도구 준비 완료",
                    )
                }
            }.onFailure { throwable ->
                tempModelFile.delete()
                preferences.edit().putBoolean(downloadInProgressKey, false).apply()
                _state.update {
                    it.copy(
                        downloadStatus = DownloadStatus.Failed,
                        progress = 0f,
                        statusMessage = throwable.message ?: "앱 생성 도구를 받지 못했습니다.",
                    )
                }
            }
        }
    }

    fun requireToolForCreation() {
        if (!_state.value.canCreateApp) {
            _state.update {
                it.copy(
                    downloadStatus = if (it.downloadStatus == DownloadStatus.Failed) DownloadStatus.Failed else DownloadStatus.NotDownloaded,
                )
            }
        }
    }

    private fun initialStatus(model: AppCreationToolModel): DownloadStatus {
        if (isDownloadMarkedActive()) return DownloadStatus.Downloading
        return if (isInstalled(model)) DownloadStatus.Ready else DownloadStatus.NotDownloaded
    }

    private fun markDownloadActive() {
        preferences.edit()
            .putBoolean(downloadInProgressKey, true)
            .putLong(downloadStartedAtKey, System.currentTimeMillis())
            .apply()
    }

    private fun isDownloadMarkedActive(): Boolean {
        if (!preferences.getBoolean(downloadInProgressKey, false)) return false
        val startedAt = preferences.getLong(downloadStartedAtKey, 0L)
        val stale = startedAt <= 0L || System.currentTimeMillis() - startedAt > staleDownloadAfterMs
        if (stale) {
            preferences.edit().putBoolean(downloadInProgressKey, false).apply()
            tempModelFile.delete()
            return false
        }
        return true
    }

    private fun isInstalled(model: AppCreationToolModel): Boolean {
        if (!modelFile.exists() || modelFile.length() <= 0L) return false
        val installedModel = preferences.getString(installedModelKey, null)
        return installedModel == null || installedModel == model.name
    }

    private suspend fun downloadModel(model: AppCreationToolModel) = withContext(Dispatchers.IO) {
        modelDirectory.mkdirs()
        tempModelFile.delete()

        val connection = (URL(model.downloadUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Onion Android")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                error("Hugging Face 다운로드 실패: HTTP $responseCode")
            }

            val total = connection.contentLengthLong.takeIf { it > 0L } ?: 0L
            connection.inputStream.use { input ->
                tempModelFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val progress = if (total > 0L) {
                            (downloaded.toFloat() / total.toFloat()).coerceIn(0.01f, 0.99f)
                        } else {
                            0.08f
                        }
                        _state.update {
                            it.copy(
                                progress = progress,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                statusMessage = "Hugging Face에서 ${model.label} 준비 중",
                            )
                        }
                    }
                }
            }

            if (tempModelFile.length() == 0L) {
                error("다운로드된 앱 생성 도구 파일이 비어 있습니다.")
            }

            if (modelFile.exists()) modelFile.delete()
            if (!tempModelFile.renameTo(modelFile)) {
                tempModelFile.copyTo(modelFile, overwrite = true)
                tempModelFile.delete()
            }
        } finally {
            connection.disconnect()
        }
    }
}
