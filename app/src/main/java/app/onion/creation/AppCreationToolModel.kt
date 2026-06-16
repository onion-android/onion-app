package app.onion.creation

enum class AppCreationToolModel(
    val label: String,
    val description: String,
    private val minimumMemoryGb: Int,
    val huggingFaceRepo: String,
    val huggingFaceFileName: String,
) {
    Gemma4TwoB(
        label = "Gemma 4 E2B",
        description = "가볍고 빠른 기본 앱 생성 도구",
        minimumMemoryGb = 4,
        huggingFaceRepo = "litert-community/gemma-4-E2B-it-litert-lm",
        huggingFaceFileName = "gemma-4-E2B-it.litertlm",
    ),
    Gemma4FourB(
        label = "Gemma 4 E4B",
        description = "여유 있는 기기에서 더 풍부한 결과를 위한 앱 생성 도구",
        minimumMemoryGb = 8,
        huggingFaceRepo = "litert-community/gemma-4-E4B-it-litert-lm",
        huggingFaceFileName = "gemma-4-E4B-it.litertlm",
    );

    fun isSupported(memoryGb: Int): Boolean = memoryGb >= minimumMemoryGb

    val downloadUrl: String
        get() = "https://huggingface.co/$huggingFaceRepo/resolve/main/$huggingFaceFileName"

    companion object {
        fun recommendFor(memoryGb: Int): AppCreationToolModel {
            return if (Gemma4FourB.isSupported(memoryGb)) {
                Gemma4FourB
            } else {
                Gemma4TwoB
            }
        }
    }
}
