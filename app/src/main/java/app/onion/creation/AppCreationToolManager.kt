package app.onion.creation

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AppCreationToolManager(
    recommendedModel: AppCreationToolModel,
) {
    private val _state = MutableStateFlow(
        AppCreationToolState(
            selectedModel = recommendedModel,
            recommendedModel = recommendedModel,
            downloadStatus = DownloadStatus.NotDownloaded,
        ),
    )
    val state: StateFlow<AppCreationToolState> = _state.asStateFlow()

    fun selectModel(model: AppCreationToolModel) {
        _state.update {
            it.copy(
                selectedModel = model,
                downloadStatus = DownloadStatus.NotDownloaded,
                progress = 0f,
            )
        }
    }

    suspend fun startDownloadInBackground() {
        if (_state.value.downloadStatus == DownloadStatus.Downloading) return

        _state.update {
            it.copy(downloadStatus = DownloadStatus.Downloading, progress = 0.06f)
        }

        // Placeholder for WorkManager-backed model download.
        repeat(10) { step ->
            delay(180)
            _state.update {
                it.copy(progress = ((step + 1) / 10f).coerceAtMost(1f))
            }
        }

        _state.update {
            it.copy(downloadStatus = DownloadStatus.Ready, progress = 1f)
        }
    }

    fun requireToolForCreation() {
        if (!_state.value.canCreateApp) {
            _state.update {
                it.copy(downloadStatus = DownloadStatus.NotDownloaded)
            }
        }
    }
}
