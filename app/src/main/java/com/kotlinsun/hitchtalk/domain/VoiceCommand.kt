package com.kotlinsun.hitchtalk.domain

import java.util.Locale

internal enum class VoiceCommand {
    PAUSE, RESUME;

    companion object {
        private val spaces = Regex("\\s+")
        private val pause = setOf(
            "be quiet", "please be quiet", "pause conversation", "pause the conversation",
            "잠깐 조용히 해줘", "잠깐 조용히 해 줘", "대화 멈춰", "대화 멈춰줘", "대화 잠깐 멈춰",
        )
        private val resume = setOf(
            "resume", "resume conversation", "resume the conversation", "let's talk", "let's talk again",
            "다시 대화", "다시 대화하자", "대화 재개", "대화 다시 시작",
        )

        fun detect(text: String): VoiceCommand? {
            val normalized = text.lowercase(Locale.ROOT).replace('’', '\'')
                .trim { it.isWhitespace() || it in ".!?。！？" }
                .replace(spaces, " ")
            return when (normalized) {
                in pause -> PAUSE
                in resume -> RESUME
                else -> null
            }
        }
    }
}
