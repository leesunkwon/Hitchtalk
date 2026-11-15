package com.kotlinsun.hitchtalk.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandTest {
    @Test
    fun acceptsExplicitCommandsInEitherLanguage() {
        assertEquals(VoiceCommand.PAUSE, VoiceCommand.detect(" Please   be quiet! "))
        assertEquals(VoiceCommand.PAUSE, VoiceCommand.detect("잠깐 조용히 해 줘."))
        assertEquals(VoiceCommand.RESUME, VoiceCommand.detect("Let’s talk again."))
        assertEquals(VoiceCommand.RESUME, VoiceCommand.detect("대화 재개!"))
    }

    @Test
    fun leavesConversationAboutCommandsUntouched() {
        assertNull(VoiceCommand.detect("My resume is ready."))
        assertNull(VoiceCommand.detect("Let's talk about food."))
        assertNull(VoiceCommand.detect("Please don't be quiet."))
        assertNull(VoiceCommand.detect("대화 재개가 무슨 뜻인가요?"))
    }
}
