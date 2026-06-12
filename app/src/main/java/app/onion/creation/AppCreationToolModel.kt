package app.onion.creation

enum class AppCreationToolModel(
    val label: String,
    val description: String,
    private val minimumMemoryGb: Int,
) {
    Gemma4TwoB(
        label = "Gemma 4 2B",
        description = "가볍고 빠른 기본 앱 생성 도구",
        minimumMemoryGb = 4,
    ),
    Gemma4FourB(
        label = "Gemma 4 4B",
        description = "여유 있는 기기에서 더 풍부한 결과를 위한 앱 생성 도구",
        minimumMemoryGb = 8,
    );

    fun isSupported(memoryGb: Int): Boolean = memoryGb >= minimumMemoryGb

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
