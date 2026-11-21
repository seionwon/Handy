package com.pais.handy.data

data class WhisperModelInfo(
    val id: String,
    val name: String,
    val filename: String,
    val url: String,
    val sizeBytes: Long,
    val speedGrade: String,
    val accuracyGrade: String,
    val recommended: Boolean = false,
    val descriptionKo: String,
    val descriptionEn: String,
) {
    val sizeMb: Int get() = ((sizeBytes + 524_288L) / 1_048_576L).toInt()
}

object ModelCatalog {
    private const val HF_BASE =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    val models: List<WhisperModelInfo> = listOf(
        WhisperModelInfo(
            id = "whisper-tiny-q5",
            name = "Whisper Tiny (Q5)",
            filename = "ggml-tiny-q5_1.bin",
            url = "${HF_BASE}ggml-tiny-q5_1.bin",
            sizeBytes = 32_152_673L,
            speedGrade = "Fastest",
            accuracyGrade = "Basic",
            descriptionKo = "가장 가볍고 빠른 오프라인 다국어 모델 (31 MB)",
            descriptionEn = "Lightest and fastest offline multilingual model (31 MB)",
        ),
        WhisperModelInfo(
            id = "whisper-base-q5",
            name = "Whisper Base (Q5)",
            filename = "ggml-base-q5_1.bin",
            url = "${HF_BASE}ggml-base-q5_1.bin",
            sizeBytes = 59_707_625L,
            speedGrade = "Fast",
            accuracyGrade = "Good",
            recommended = true,
            descriptionKo = "모바일 추천 균형형 오프라인 한국어·다국어 모델 (57 MB)",
            descriptionEn = "Recommended balanced offline Korean & multilingual model (57 MB)",
        ),
        WhisperModelInfo(
            id = "whisper-tiny",
            name = "Whisper Tiny (FP16)",
            filename = "ggml-tiny.bin",
            url = "${HF_BASE}ggml-tiny.bin",
            sizeBytes = 77_691_713L,
            speedGrade = "Fast",
            accuracyGrade = "Basic+",
            descriptionKo = "비양자화 Tiny 오프라인 모델 (74 MB)",
            descriptionEn = "Unquantized Tiny offline model (74 MB)",
        ),
        WhisperModelInfo(
            id = "whisper-base",
            name = "Whisper Base (FP16)",
            filename = "ggml-base.bin",
            url = "${HF_BASE}ggml-base.bin",
            sizeBytes = 147_951_465L,
            speedGrade = "Medium",
            accuracyGrade = "Good+",
            descriptionKo = "높은 품질의 Base 오프라인 모델 (141 MB)",
            descriptionEn = "Full-precision Base offline model (141 MB)",
        ),
        WhisperModelInfo(
            id = "whisper-small-q5",
            name = "Whisper Small (Q5)",
            filename = "ggml-small-q5_1.bin",
            url = "${HF_BASE}ggml-small-q5_1.bin",
            sizeBytes = 190_085_487L,
            speedGrade = "Moderate",
            accuracyGrade = "High",
            descriptionKo = "정확도가 뛰어난 한국어·다국어 오프라인 모델 (181 MB)",
            descriptionEn = "High-accuracy Korean & multilingual offline model (181 MB)",
        ),
    )

    fun byId(id: String): WhisperModelInfo? = models.firstOrNull { it.id == id }
}
