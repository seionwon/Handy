package com.pais.handy.data

import java.io.File
import java.security.MessageDigest

data class WhisperModelInfo(
    val id: String,
    val name: String,
    val filename: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val speedGrade: String,
    val accuracyGrade: String,
    val recommended: Boolean = false,
    val descriptionKo: String,
    val descriptionEn: String,
) {
    internal fun matches(file: File): Boolean {
        if (!file.isFile || file.length() != sizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == sha256
    }

    val sizeMb: Int get() = ((sizeBytes + 524_288L) / 1_048_576L).toInt()
}

object ModelCatalog {
    private const val HF_BASE =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/"

    val models: List<WhisperModelInfo> = listOf(
        WhisperModelInfo(
            id = "whisper-tiny-q5",
            name = "Whisper Tiny (Q5)",
            filename = "ggml-tiny-q5_1.bin",
            url = "${HF_BASE}ggml-tiny-q5_1.bin",
            sizeBytes = 32_152_673L,
            sha256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
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
            sha256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
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
            sha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
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
            sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
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
            sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
            speedGrade = "Moderate",
            accuracyGrade = "High",
            descriptionKo = "정확도가 뛰어난 한국어·다국어 오프라인 모델 (181 MB)",
            descriptionEn = "High-accuracy Korean & multilingual offline model (181 MB)",
        ),
    )

    fun byId(id: String): WhisperModelInfo? = models.firstOrNull { it.id == id }
}
