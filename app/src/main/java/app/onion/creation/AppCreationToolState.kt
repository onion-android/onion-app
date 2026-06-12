package app.onion.creation

data class AppCreationToolState(
    val selectedModel: AppCreationToolModel,
    val recommendedModel: AppCreationToolModel,
    val downloadStatus: DownloadStatus,
    val progress: Float = 0f,
) {
    val canCreateApp: Boolean
        get() = downloadStatus == DownloadStatus.Ready
}

enum class DownloadStatus {
    NotDownloaded,
    Downloading,
    Ready,
}
