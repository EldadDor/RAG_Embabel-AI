package com.dex.ragpoc.catalog

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.ModelProfile
import com.dex.ragpoc.parsing.DocumentChunker
import com.dex.ragpoc.persistence.JdbcModelProfileRepository
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class DocumentListChanged : RuntimeException()

class DocumentListUnavailable(
    val reason: String = "catalog_not_ready",
) : RuntimeException()

data class DocumentScope(
    @get:JsonProperty("model_profile") val modelProfile: String,
    @get:JsonProperty("chunking_profile") val chunkingProfile: String,
)

data class DocumentSummary(
    @get:JsonProperty("doc_id") val documentId: String,
    val title: String,
    @get:JsonProperty("file_name") val fileName: String,
    @get:JsonProperty("document_type") val documentType: String,
    @get:JsonProperty("last_ingested_at") val lastIngestedAt: Instant?,
    @get:JsonProperty("indexed_chunk_count") val indexedChunkCount: Long,
)

data class DocumentPage(
    val limit: Int,
    @get:JsonProperty("has_more") val hasMore: Boolean,
    @get:JsonProperty("next_cursor") val nextCursor: String?,
    @get:JsonProperty("list_revision") val listRevision: String,
    @get:JsonProperty("generated_at") val generatedAt: Instant,
)

data class DocumentListResponse(
    @get:JsonProperty("workspace_id") val workspaceId: String,
    val scope: DocumentScope,
    val items: List<DocumentSummary>,
    val page: DocumentPage,
)

data class CatalogSnapshot(
    val revision: String,
    val rows: List<DocumentSummary>,
)

interface DocumentCatalogReader {
    fun page(
        workspace: String,
        model: String,
        chunking: String,
        limit: Int,
        cursor: CatalogCursor?,
    ): CatalogSnapshot
}

data class CatalogCursor(
    val workspace: String,
    val model: String,
    val chunking: String,
    val subject: String,
    val limit: Int,
    val revision: String,
    val lastId: String,
    val lastTime: Instant?,
    val issued: Long,
    val expires: Long,
)

/** Python json.dumps(..., ensure_ascii=True, separators=(",", ":"), sort_keys=True). */
object PythonJson {
    fun encode(
        value: Any?,
        ascii: Boolean = true,
        spaced: Boolean = false,
    ): String =
        when (value) {
            null -> {
                "null"
            }

            is String -> {
                buildString {
                    append('"')
                    value.forEach { char ->
                        when (char) {
                            '"' -> append("\\\"")
                            '\\' -> append("\\\\")
                            '\b' -> append("\\b")
                            '\t' -> append("\\t")
                            '\n' -> append("\\n")
                            '\u000c' -> append("\\f")
                            '\r' -> append("\\r")
                            else -> if (char.code < 32 || (ascii && char.code >= 127)) append("\\u%04x".format(char.code)) else append(char)
                        }
                    }
                    append('"')
                }
            }

            is Map<*, *> -> {
                value.entries.joinToString(if (spaced) ", " else ",", "{", "}") {
                    encode(it.key, ascii, spaced) + (if (spaced) ": " else ":") + encode(it.value, ascii, spaced)
                }
            }

            is Iterable<*> -> {
                value.joinToString(if (spaced) ", " else ",", "[", "]") { encode(it, ascii, spaced) }
            }

            is Number, is Boolean -> {
                value.toString()
            }

            else -> {
                error("Unsupported Python JSON value")
            }
        }
}

class DocumentCursorCodec(
    private val secret: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mapper = ObjectMapper()

    fun subject(subject: String): String = sign(("subject\u0000" + subject).toByteArray(StandardCharsets.UTF_8))

    fun encode(cursor: CatalogCursor): String {
        val payload =
            sortedMapOf<String, Any?>(
                "v" to 1,
                "workspace" to cursor.workspace,
                "model" to cursor.model,
                "chunking" to cursor.chunking,
                "subject" to cursor.subject,
                "limit" to cursor.limit,
                "revision" to cursor.revision,
                "last_id" to cursor.lastId,
                "last_time" to cursor.lastTime?.toString(),
                "issued" to cursor.issued,
                "expires" to cursor.expires,
            )
        val data = Base64.getUrlEncoder().withoutPadding().encodeToString(PythonJson.encode(payload).toByteArray(StandardCharsets.UTF_8))
        return "$data.${sign(data.toByteArray(StandardCharsets.US_ASCII))}"
    }

    fun decode(token: String): CatalogCursor {
        require(token.length in 1..4096)
        val parts = token.split('.')
        require(parts.size == 2 && Regex("^[A-Za-z0-9_-]+$").matches(parts[0]))
        require(MessageDigest.isEqual(sign(parts[0].toByteArray(StandardCharsets.US_ASCII)).toByteArray(), parts[1].toByteArray()))
        try {
            val root = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]))
            require(root.isObject && root["v"]?.isIntegralNumber == true && root["v"].canConvertToInt() && root["v"].asInt() == 1)

            fun text(name: String): String {
                val field = root[name]
                require(field != null && field.isTextual && field.asText().isNotEmpty())
                return field.asText()
            }

            fun number(name: String): Long {
                val field = root[name]
                require(field != null && field.isIntegralNumber && field.canConvertToLong())
                return field.longValue()
            }
            val revision = text("revision")
            require(Regex("^[0-9]+$").matches(revision))
            val limit = number("limit")
            require(limit in 1..100)
            val issued = number("issued")
            val expires = number("expires")
            require(issued <= clock.instant().epochSecond + 30 && issued <= Long.MAX_VALUE - 900 && expires == issued + 900)
            val time = root["last_time"]
            require(time == null || time.isNull || time.isTextual)
            val lastTime = if (time == null || time.isNull) null else OffsetDateTime.parse(time.asText()).toInstant()
            return CatalogCursor(
                text("workspace"),
                text("model"),
                text("chunking"),
                text("subject"),
                limit.toInt(),
                revision,
                text("last_id"),
                lastTime,
                issued,
                expires,
            )
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid document cursor", error)
        }
    }

    private fun sign(bytes: ByteArray): String {
        if (secret.isEmpty()) throw DocumentListUnavailable()
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            doFinal(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}

@Service
class DocumentCatalogService(
    private val properties: AppProperties,
    private val reader: DocumentCatalogReader,
    private val profiles: JdbcModelProfileRepository,
    private val chunker: DocumentChunker,
) {
    private val clock: Clock = Clock.systemUTC()
    private val cursors = DocumentCursorCodec(properties.documents.cursorSecret, clock)

    fun list(
        workspace: String,
        subject: String,
        limit: Int = 25,
        cursor: String? = null,
        modelProfile: String? = null,
        chunkingProfile: String? = null,
    ): DocumentListResponse {
        require(workspace.isNotEmpty() && limit in 1..100)
        listOfNotNull(modelProfile, chunkingProfile).forEach { require(it.length in 1..100 && PROFILE_NAME.matches(it)) }
        if (!properties.documents.enabled || properties.database.vectorStore != "postgres" ||
            properties.documents.cursorSecret
                .toByteArray(StandardCharsets.UTF_8)
                .size < 32
        ) {
            throw DocumentListUnavailable(
                when {
                    !properties.documents.enabled -> "DOCUMENT_LIST_ENABLED is false"
                    properties.database.vectorStore != "postgres" -> "postgres storage is required"
                    else -> "DOCUMENT_LIST_CURSOR_SECRET must contain at least 32 UTF-8 bytes"
                },
            )
        }
        val token = cursor?.let(cursors::decode)
        val model = modelProfile ?: properties.rag.modelProfile
        val chunking = chunkingProfile ?: properties.rag.defaultChunkingProfile
        if (!chunker.supportsProfile(chunking)) {
            if (chunkingProfile != null) throw IllegalArgumentException("Unknown profile") else throw DocumentListUnavailable()
        }
        if (token != null) {
            require(token.workspace == workspace && token.subject == cursors.subject(subject) && token.limit == limit)
            if (token.model != model || token.chunking != chunking) {
                if ((token.model != model && modelProfile != null) || (token.chunking != chunking && chunkingProfile != null)) {
                    throw IllegalArgumentException("Cursor scope changed")
                }
                throw DocumentListChanged()
            }
            if (clock.instant().epochSecond >= token.expires) throw DocumentListChanged()
        }
        val snapshot =
            try {
                val profile = profiles.get(model)
                if (!available(profile)) {
                    if (modelProfile != null) throw IllegalArgumentException("Unavailable profile") else throw DocumentListUnavailable()
                }
                reader.page(workspace, model, chunking, limit, token)
            } catch (error: DataAccessException) {
                throw DocumentListUnavailable("catalog database operation failed (${error.javaClass.simpleName})")
            }
        val hasMore = snapshot.rows.size > limit
        val rows = snapshot.rows.take(limit)
        check(rows.all { it.indexedChunkCount in 0..9007199254740991L }) { "Invalid stored document count" }
        val now = clock.instant()
        val next =
            if (hasMore) {
                rows.last().let { last ->
                    cursors.encode(
                        CatalogCursor(
                            workspace,
                            model,
                            chunking,
                            cursors.subject(subject),
                            limit,
                            snapshot.revision,
                            last.documentId,
                            last.lastIngestedAt,
                            token?.issued ?: now.epochSecond,
                            token?.expires ?: now.epochSecond + 900,
                        ),
                    )
                }
            } else {
                null
            }
        return DocumentListResponse(
            workspace,
            DocumentScope(model, chunking),
            rows,
            DocumentPage(limit, hasMore, next, snapshot.revision, now),
        )
    }

    private fun available(profile: ModelProfile?): Boolean =
        profile != null && profile.status == "ready" &&
            profile.provider == properties.embedding.provider

    companion object {
        val PROFILE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9_-]*$")
    }
}
