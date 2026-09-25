package com.dex.ragpoc.persistence

import com.dex.ragpoc.config.AppProperties
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

class JdbcConversationRepositoryTest {
    private val repository = JdbcConversationRepository(mockk<JdbcTemplate>(relaxed = true), AppProperties())

    @Test
    fun `rejects unsupported conversation roles before writing`() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.appendTurn("session-1", "system", "not persisted")
        }
    }

    @Test
    fun `rejects an empty conversation turn before writing`() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.appendTurn("session-1", "user", "   ")
        }
    }

    @Test
    fun `bounds recent conversation history requests`() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.recentTurns("session-1", 101)
        }
    }
}
