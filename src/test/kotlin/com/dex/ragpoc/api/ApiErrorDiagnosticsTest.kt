package com.dex.ragpoc.api

import com.dex.ragpoc.catalog.DocumentListUnavailable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(OutputCaptureExtension::class)
class ApiErrorDiagnosticsTest {
    @Test
    fun `unavailable reason is logged but remains private to server`(output: CapturedOutput) {
        val response = ApiErrorAdvice().documentListUnavailable(DocumentListUnavailable("DOCUMENT_LIST_ENABLED is false"))
        assertEquals(503, response.statusCode.value())
        assertEquals("The document list is temporarily unavailable.", response.body?.message)
        assertTrue(output.all.contains("DOCUMENT_LIST_ENABLED is false"))
    }

    @Test
    fun `unexpected errors log class and location without sensitive message`(output: CapturedOutput) {
        val response = ApiErrorAdvice().unexpectedFailure(IllegalStateException("sensitive-database-value"))
        assertEquals(500, response.statusCode.value())
        assertTrue(output.all.contains("java.lang.IllegalStateException"))
        assertFalse(output.all.contains("sensitive-database-value"))
        assertFalse(response.body.toString().contains("sensitive-database-value"))
    }
}
