package com.dex.ragpoc.evaluation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.math.roundToInt

data class GoldenCase(
    val id: String,
    val question: String,
    val expectedFacts: List<String>,
    val expectedSourceHints: List<String>,
    val workspaceId: String,
    val chunkingProfile: String,
)

data class EvaluationEvidence(
    val chunkId: String,
    val sourcePath: String,
    val text: String,
)

data class EvaluationAttempt(
    val answer: String,
    val evidence: List<EvaluationEvidence>,
    val latencyMillis: Long,
    val failureStage: String? = null,
)

fun interface GoldenCaseExecutor {
    fun execute(case: GoldenCase): EvaluationAttempt
}

data class GoldenCaseReport(
    val id: String,
    val repeatedRetrievalMatches: Boolean,
    val sourcePrecision: Double,
    val sourceRecall: Double,
    val sourceMrr: Double,
    val expectedFactCoverage: Double,
    val faithfulnessProxy: Double,
    val latencyMillis: Long,
    val failureStage: String?,
)

data class GoldenEvaluationReport(
    val cases: List<GoldenCaseReport>,
    val configuration: Map<String, String>,
)

class GoldenEvaluationRunner(
    private val mapper: ObjectMapper,
) {
    fun load(path: Path): List<GoldenCase> =
        path
            .readLines()
            .mapIndexedNotNull { index, line -> line.takeIf(String::isNotBlank)?.let { parse(it, index + 1) } }
            .also { cases -> require(cases.isNotEmpty()) { "Golden-case dataset must not be empty" } }
            .also { cases -> require(cases.map(GoldenCase::id).distinct().size == cases.size) { "Golden-case IDs must be unique" } }

    fun evaluate(
        cases: List<GoldenCase>,
        executor: GoldenCaseExecutor,
        configuration: Map<String, String>,
    ): GoldenEvaluationReport =
        GoldenEvaluationReport(
            cases.map { case -> evaluateCase(case, executor) },
            configuration.toSortedMap(),
        )

    fun write(report: GoldenEvaluationReport): String =
        mapper.writeValueAsString(
            mapOf(
                "configuration" to report.configuration,
                "cases" to
                    report.cases.map {
                        mapOf(
                            "id" to it.id,
                            "repeated_retrieval_matches" to it.repeatedRetrievalMatches,
                            "source_precision" to it.sourcePrecision,
                            "source_recall" to it.sourceRecall,
                            "source_mrr" to it.sourceMrr,
                            "expected_fact_coverage" to it.expectedFactCoverage,
                            "faithfulness_proxy" to it.faithfulnessProxy,
                            "latency_ms" to it.latencyMillis,
                            "failure_stage" to it.failureStage,
                        )
                    },
            ),
        )

    private fun parse(
        line: String,
        lineNumber: Int,
    ): GoldenCase {
        val node =
            runCatching {
                mapper.readTree(
                    line,
                )
            }.getOrElse { throw IllegalArgumentException("Malformed JSONL at line $lineNumber", it) }
        return GoldenCase(
            requiredText(node, "id", lineNumber),
            requiredText(node, "question", lineNumber),
            requiredStrings(node, "expected_facts", lineNumber),
            requiredStrings(node, "expected_source_hints", lineNumber),
            node.path("workspace_id").takeIf(JsonNode::isTextual)?.asText() ?: "local",
            requiredText(node, "chunking_profile", lineNumber),
        )
    }

    private fun evaluateCase(
        case: GoldenCase,
        executor: GoldenCaseExecutor,
    ): GoldenCaseReport {
        val first = executor.execute(case)
        val second = executor.execute(case)
        val evidence = first.evidence
        val matchingRanks =
            evidence.mapIndexedNotNull {
                index,
                item,
                ->
                (index + 1).takeIf { matchesHint(item.sourcePath, case.expectedSourceHints) }
            }
        val matchingSources = evidence.count { matchesHint(it.sourcePath, case.expectedSourceHints) }
        val foundHints =
            case.expectedSourceHints.count { hint ->
                evidence.any { item -> item.sourcePath.contains(hint, ignoreCase = true) }
            }
        val factHits = case.expectedFacts.count { fact -> first.answer.contains(fact, ignoreCase = true) }
        val answerTerms =
            first.answer
                .split(Regex("[^\\p{L}\\p{N}]+"), limit = 0)
                .filter { it.length >= 4 }
                .toSet()
        val evidenceTerms = evidence.flatMap { it.text.split(Regex("[^\\p{L}\\p{N}]+"), limit = 0) }.filter { it.length >= 4 }.toSet()
        return GoldenCaseReport(
            case.id,
            first.evidence.map(EvaluationEvidence::chunkId) == second.evidence.map(EvaluationEvidence::chunkId),
            ratio(matchingSources, evidence.size),
            ratio(foundHints, case.expectedSourceHints.size),
            matchingRanks.firstOrNull()?.let { 1.0 / it } ?: 0.0,
            ratio(factHits, case.expectedFacts.size),
            ratio(answerTerms.count { it in evidenceTerms }, answerTerms.size),
            listOf(first.latencyMillis, second.latencyMillis).average().roundToInt().toLong(),
            first.failureStage ?: second.failureStage,
        )
    }

    private fun matchesHint(
        sourcePath: String,
        hints: List<String>,
    ): Boolean = hints.any { sourcePath.contains(it, ignoreCase = true) }

    private fun requiredText(
        node: JsonNode,
        field: String,
        lineNumber: Int,
    ): String =
        node.path(field).takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()
            ?: throw IllegalArgumentException("Missing $field at line $lineNumber")

    private fun requiredStrings(
        node: JsonNode,
        field: String,
        lineNumber: Int,
    ): List<String> =
        node
            .path(field)
            .takeIf(JsonNode::isArray)
            ?.mapNotNull { it.takeIf(JsonNode::isTextual)?.asText()?.takeIf(String::isNotBlank) }
            ?.takeIf(List<String>::isNotEmpty) ?: throw IllegalArgumentException("Missing $field at line $lineNumber")

    private fun ratio(
        numerator: Int,
        denominator: Int,
    ): Double = if (denominator == 0) 0.0 else numerator.toDouble() / denominator
}
