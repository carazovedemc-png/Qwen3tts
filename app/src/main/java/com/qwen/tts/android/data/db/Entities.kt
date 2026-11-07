package com.qwen.tts.android.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "voice_profiles")
data class VoiceProfileEntity(
    @PrimaryKey val voiceId: String,
    val name: String,
    val referenceWavPath: String,
    val speakerEmbeddingPath: String,
    val durationMillis: Long,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "generations")
data class GenerationEntity(
    @PrimaryKey(autoGenerate = true) val generationId: Long = 0,
    val text: String,
    val voiceId: String?,
    val voiceName: String,
    val wavPath: String,
    val sampleRate: Int,
    val sampleCount: Int,
    val synthesisMillis: Long,
    val createdAt: Long = System.currentTimeMillis(),
)
