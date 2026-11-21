package com.pais.handy.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.pais.handy.stt.TextPostProcessor
import java.io.File

data class HistoryEntry(
    val id: Long,
    val createdAtMs: Long,
    val title: String,
    val audioFileName: String?,
    val sourceName: String?,
    val language: String,
    val engineLabel: String,
    val transcriptionText: String,
    val postProcessedText: String?,
    val saved: Boolean,
) {
    val displayText: String
        get() = postProcessedText?.takeIf { it.isNotBlank() } ?: transcriptionText
}

class HistoryDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    val recordingsDir: File = File(context.applicationContext.filesDir, "recordings").apply {
        mkdirs()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE history_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at_ms INTEGER NOT NULL,
                title TEXT NOT NULL,
                audio_file_name TEXT,
                source_name TEXT,
                language TEXT NOT NULL,
                engine_label TEXT NOT NULL,
                transcription_text TEXT NOT NULL,
                post_processed_text TEXT,
                saved INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Initial v1 schema
    }

    fun resolveAudioFile(fileName: String?): File? {
        if (fileName.isNullOrBlank() || !TextPostProcessor.isSafeRecordingFileName(fileName)) {
            return null
        }
        val root = recordingsDir.canonicalFile
        val target = File(root, fileName).canonicalFile
        if (target.parentFile != root) return null
        return target
    }

    fun insert(
        title: String,
        audioFileName: String?,
        sourceName: String?,
        language: String,
        engineLabel: String,
        transcriptionText: String,
        postProcessedText: String?,
    ): Long {
        val safeAudio = audioFileName?.takeIf { TextPostProcessor.isSafeRecordingFileName(it) }
        val values = ContentValues().apply {
            put("created_at_ms", System.currentTimeMillis())
            put("title", title)
            put("audio_file_name", safeAudio)
            put("source_name", sourceName)
            put("language", language)
            put("engine_label", engineLabel)
            put("transcription_text", transcriptionText)
            put("post_processed_text", postProcessedText)
            put("saved", 0)
        }
        return writableDatabase.insert("history_entries", null, values)
    }

    fun listAll(): List<HistoryEntry> {
        val result = mutableListOf<HistoryEntry>()
        readableDatabase.query(
            "history_entries",
            arrayOf(
                "id",
                "created_at_ms",
                "title",
                "audio_file_name",
                "source_name",
                "language",
                "engine_label",
                "transcription_text",
                "post_processed_text",
                "saved",
            ),
            null,
            null,
            null,
            null,
            "created_at_ms DESC, id DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += HistoryEntry(
                    id = cursor.getLong(0),
                    createdAtMs = cursor.getLong(1),
                    title = cursor.getString(2),
                    audioFileName = if (cursor.isNull(3)) null else cursor.getString(3),
                    sourceName = if (cursor.isNull(4)) null else cursor.getString(4),
                    language = cursor.getString(5),
                    engineLabel = cursor.getString(6),
                    transcriptionText = cursor.getString(7),
                    postProcessedText = if (cursor.isNull(8)) null else cursor.getString(8),
                    saved = cursor.getInt(9) != 0,
                )
            }
        }
        return result
    }

    fun toggleSaved(id: Long) {
        writableDatabase.execSQL(
            "UPDATE history_entries SET saved = CASE WHEN saved = 0 THEN 1 ELSE 0 END WHERE id = ?",
            arrayOf(id),
        )
    }

    fun updateTranscription(
        id: Long,
        transcriptionText: String,
        postProcessedText: String?,
        engineLabel: String,
    ) {
        val values = ContentValues().apply {
            put("transcription_text", transcriptionText)
            put("post_processed_text", postProcessedText)
            put("engine_label", engineLabel)
        }
        writableDatabase.update("history_entries", values, "id = ?", arrayOf(id.toString()))
    }

    fun deleteEntry(entry: HistoryEntry) {
        writableDatabase.delete("history_entries", "id = ?", arrayOf(entry.id.toString()))
        resolveAudioFile(entry.audioFileName)?.let { file ->
            if (file.isFile) file.delete()
        }
    }

    companion object {
        private const val DB_NAME = "handy_history.db"
        private const val DB_VERSION = 1
    }
}
