package app.onion.creation

data class AppCreationToolState(
    val selectedModel: AppCreationToolModel,
    val recommendedModel: AppCreationToolModel,
    val downloadStatus: DownloadStatus,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val statusMessage: String? = null,
) {
    val canCreateApp: Boolean
        get() = downloadStatus == DownloadStatus.Ready

    val canChangeModel: Boolean
        get() = downloadStatus != DownloadStatus.Downloading
}

enum class DownloadStatus {
    NotDownloaded,
    Downloading,
    Ready,
    Failed,
}
