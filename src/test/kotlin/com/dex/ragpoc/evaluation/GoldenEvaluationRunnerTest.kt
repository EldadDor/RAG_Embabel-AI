package com.dex.ragpoc.evaluation

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoldenEvaluationRunnerTest {
    private val runner = GoldenEvaluationRunner(ObjectMapper())

    @Test
    fun `loads checked in English and Hebrew golden cases`() {
        assertEquals(19, runner.load(Path.of("evaluation", "golden-cases.jsonl")).size)
        assertEquals(13, runner.load(Path.of("evaluation", "golden-cases-heb.jsonl")).size)
    }

    @Test
    fun `rejects malformed duplicate and incomplete golden cases`() {
        val malformed = Files.createTempFile("golden-malformed", ".jsonl")
        val duplicate = Files.createTempFile("golden-duplicate", ".jsonl")
        val incomplete = Files.createTempFile("golden-incomplete", ".jsonl")
        val empty = Files.createTempFile("golden-empty", ".jsonl")
        Files.writeString(malformed, "not-json")
        Files.writeString(duplicate, validCase("case") + "\n" + validCase("case"))
        Files.writeString(incomplete, "{\"id\":\"missing-fields\"}")
        Files.writeString(empty, "")

        assertFailsWith<IllegalArgumentException> { runner.load(malformed) }
        assertFailsWith<IllegalArgumentException> { runner.load(duplicate) }
        assertFailsWith<IllegalArgumentException> { runner.load(incomplete) }
        assertFailsWith<IllegalArgumentException> { runner.load(empty) }
    }

    @Test
    fun `produces deterministic metrics and portable report`() {
        val case = GoldenCase("soil", "What defines soil?", listOf("particles"), listOf("soil.pdf"), "alpha", "default")
        val report =
            runner.evaluate(
                listOf(case),
                GoldenCaseExecutor {
                    EvaluationAttempt(
                        "Soil has particles.",
                        listOf(EvaluationEvidence("chunk-1", "guide/soil.pdf", "Soil is a mixture of particles.")),
                        8,
                    )
                },
                mapOf("model_profile" to "bge-m3", "storage" to "postgres"),
            )

        val result = report.cases.single()
        assertTrue(result.repeatedRetrievalMatches)
        assertEquals(1.0, result.sourcePrecision)
        assertEquals(1.0, result.sourceRecall)
        assertEquals(1.0, result.sourceMrr)
        assertEquals(1.0, result.expectedFactCoverage)
        assertTrue(result.faithfulnessProxy > 0.0)
        assertContains(runner.write(report), "\"storage\":\"postgres\"")
    }

    private fun validCase(id: String): String =
        "{\"id\":\"$id\",\"question\":\"Question\",\"expected_facts\":[\"Fact\"],\"expected_source_hints\":[\"guide.pdf\"],\"chunking_profile\":\"default\"}"
}
