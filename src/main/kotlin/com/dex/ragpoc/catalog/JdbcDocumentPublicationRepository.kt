package com.dex.ragpoc.catalog

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.Document
import com.dex.ragpoc.persistence.requireIdentifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Instant

data class PublishedSource(
    val documentId: String,
    val sourcePath: String,
    val contentHash: String?,
    val updatedAt: Instant,
)

data class DisplayMetadata(
    val title: String,
    val fileName: String,
    val type: String,
)

object DocumentDisplay {
    fun from(document: Document): DisplayMetadata {
        fun clean(
            value: String,
            maximum: Int,
        ): String {
            val normalized =
                buildString {
                    value.codePoints().forEach { point ->
                        val whitespace = Character.isWhitespace(point) || Character.isSpaceChar(point) || point == 0x85
                        if (whitespace) {
                            append(' ')
                        } else if (Character.getType(point) !in setOf(Character.CONTROL.toInt(), Character.FORMAT.toInt())) {
                            appendCodePoint(point)
                        }
                    }
                }.trim().replace(Regex(" +"), " ")
            return normalized
                .codePoints()
                .limit(maximum.toLong())
                .toArray()
                .let { String(it, 0, it.size) }
        }
        val name = clean(document.sourcePath.replace('\\', '/').substringAfterLast('/'), 255).ifEmpty { "Untitled document" }
        val rawTitle = document.title.orEmpty()
        val absolute = rawTitle.startsWith('/') || rawTitle.startsWith("\\\\") || Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(rawTitle)
        val title = clean(if (absolute) "" else rawTitle, 300).ifEmpty { name }
        return DisplayMetadata(title, name, document.sourceType.name.lowercase())
    }
}

/** All mutation methods participate in the caller's vector/source/asset transaction. */
@Repository
class JdbcDocumentPublicationRepository(
    private val jdbc: JdbcTemplate,
    properties: AppProperties,
) {
    private val schema = requireIdentifier("PG_SCHEMA", properties.database.schema)

    fun lockProfile(model: String) {
        requireTransaction()
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", { _, _ -> Unit }, profileKey(model))
    }

    fun lockDocument(
        workspace: String,
        chunking: String,
        documentId: String,
    ) {
        requireTransaction()
        jdbc.query(
            "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
            { _, _ -> Unit },
            PythonJson.encode(listOf("document", schema, workspace, chunking, documentId)),
        )
    }

    fun profileKey(model: String): String = "document-publication:$schema:$model"

    fun scanTime(): Instant = requireNotNull(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp::class.java)).toInstant()

    fun requireReady(model: String) {
        require(
            jdbc
                .query(
                    "SELECT status FROM $schema.model_profiles WHERE profile_name=?",
                    { rs, _ -> rs.getString(1) },
                    model,
                ).firstOrNull() ==
                "ready",
        ) {
            "Target model profile is not ready"
        }
    }

    fun unchanged(
        workspace: String,
        model: String,
        chunking: String,
        documentId: String,
        hash: String?,
    ): Boolean =
        hash != null &&
            jdbc
                .query(
                    "SELECT content_hash FROM $schema.document_index_metadata WHERE workspace_id=? AND model_profile=? AND chunking_profile=? AND doc_id=?",
                    { rs, _ -> rs.getString(1) },
                    workspace,
                    model,
                    chunking,
                    documentId,
                ).firstOrNull() == hash

    fun publish(
        workspace: String,
        model: String,
        chunking: String,
        table: String,
        document: Document,
        rootPath: String?,
        preserveTime: Boolean = false,
        sourceTime: Instant? = null,
        displayOverride: DisplayMetadata? = null,
    ) {
        requireTransaction()
        val target = requireIdentifier("storage target", table)
        val old =
            jdbc
                .query(
                    "SELECT title,file_name,document_type,last_ingested_at,indexed_chunk_count FROM $schema.document_index_metadata WHERE workspace_id=? AND model_profile=? AND chunking_profile=? AND doc_id=?",
                    {
                        rs,
                        _,
                        ->
                        DocumentSummary(
                            document.documentId,
                            rs.getString(1),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getTimestamp(4)?.toInstant(),
                            rs.getLong(5),
                        )
                    },
                    workspace,
                    model,
                    chunking,
                    document.documentId,
                ).firstOrNull()
        val count =
            jdbc.queryForObject(
                """SELECT count(*) FROM $schema.$target
            WHERE metadata->>'workspace_id'=? AND COALESCE(metadata->>'doc_id',metadata->>'document_id')=?
            AND COALESCE(metadata->>'chunking_profile','default')=?""",
                Long::class.java,
                workspace,
                document.documentId,
                chunking,
            ) ?: 0L
        require(count in 0..9007199254740991L)
        val time = if (preserveTime) sourceTime else jdbc.queryForObject("SELECT clock_timestamp()", Timestamp::class.java)?.toInstant()
        val display = displayOverride ?: DocumentDisplay.from(document)
        jdbc.update(
            """
            INSERT INTO $schema.document_index_metadata AS current
            (workspace_id,model_profile,chunking_profile,doc_id,title,file_name,document_type,content_hash,
             last_ingested_at,indexed_chunk_count,source_path,root_path)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (workspace_id,model_profile,chunking_profile,doc_id) DO UPDATE SET
                title=EXCLUDED.title,file_name=EXCLUDED.file_name,document_type=EXCLUDED.document_type,
                content_hash=EXCLUDED.content_hash,last_ingested_at=EXCLUDED.last_ingested_at,
                indexed_chunk_count=EXCLUDED.indexed_chunk_count,source_path=EXCLUDED.source_path,
                root_path=EXCLUDED.root_path,updated_at=clock_timestamp()
            WHERE (current.title,current.file_name,current.document_type,current.content_hash,current.last_ingested_at,
                   current.indexed_chunk_count,current.source_path,current.root_path) IS DISTINCT FROM
                  (EXCLUDED.title,EXCLUDED.file_name,EXCLUDED.document_type,EXCLUDED.content_hash,EXCLUDED.last_ingested_at,
                   EXCLUDED.indexed_chunk_count,EXCLUDED.source_path,EXCLUDED.root_path)
            """.trimIndent(),
            workspace,
            model,
            chunking,
            document.documentId,
            display.title,
            display.fileName,
            display.type,
            document.contentHash,
            time?.let(Timestamp::from),
            count,
            document.sourcePath,
            rootPath,
        )
        if (old != DocumentSummary(document.documentId, display.title, display.fileName, display.type, time, count)) {
            bumpRevision(workspace, model, chunking)
        }
    }

    fun replaceAssetReferences(
        workspace: String,
        model: String,
        chunking: String,
        documentId: String,
        assetIds: List<String>,
    ) {
        requireTransaction()
        jdbc.update(
            "DELETE FROM $schema.document_index_assets WHERE workspace_id=? AND model_profile=? AND chunking_profile=? AND doc_id=?",
            workspace,
            model,
            chunking,
            documentId,
        )
        assetIds.forEach { assetId ->
            jdbc.update(
                """INSERT INTO $schema.document_index_assets
            (workspace_id,model_profile,chunking_profile,doc_id,asset_id) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING""",
                workspace,
                model,
                chunking,
                documentId,
                assetId,
            )
        }
        pruneShared(workspace, chunking, documentId)
    }

    fun listForRoot(
        workspace: String,
        model: String,
        chunking: String,
        root: String,
    ): List<PublishedSource> =
        jdbc.query(
            "SELECT doc_id,source_path,content_hash,updated_at FROM $schema.document_index_metadata WHERE workspace_id=? AND model_profile=? AND chunking_profile=? AND root_path=? ORDER BY doc_id COLLATE \"C\"",
            {
                rs,
                _,
                ->
                PublishedSource(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant())
            },
            workspace,
            model,
            chunking,
            root,
        )

    fun deletePublication(
        workspace: String,
        model: String,
        chunking: String,
        documentId: String,
        observedAt: Instant,
    ): Boolean {
        requireTransaction()
        val deleted =
            jdbc.update(
                "DELETE FROM $schema.document_index_metadata WHERE workspace_id=? AND model_profile=? AND chunking_profile=? AND doc_id=? AND updated_at<=?",
                workspace,
                model,
                chunking,
                documentId,
                Timestamp.from(observedAt),
            ) > 0
        if (deleted) {
            bumpRevision(workspace, model, chunking)
            pruneShared(workspace, chunking, documentId)
        }
        return deleted
    }

    private fun bumpRevision(
        workspace: String,
        model: String,
        chunking: String,
    ) {
        jdbc.update(
            """INSERT INTO $schema.document_list_revisions(workspace_id,model_profile,chunking_profile,revision)
            VALUES (?,?,?,1) ON CONFLICT(workspace_id,model_profile,chunking_profile) DO UPDATE SET
            revision=$schema.document_list_revisions.revision+1,updated_at=clock_timestamp()""",
            workspace,
            model,
            chunking,
        )
    }

    private fun pruneShared(
        workspace: String,
        chunking: String,
        documentId: String,
    ) {
        // Uncertified historical publications may still own shared rows.
        if (jdbc.queryForObject("SELECT ready FROM $schema.document_catalog_state WHERE singleton=TRUE", Boolean::class.java) !=
            true
        ) {
            return
        }
        jdbc.update(
            """DELETE FROM $schema.document_assets assets WHERE workspace_id=? AND chunking_profile=? AND doc_id=?
            AND NOT EXISTS(SELECT 1 FROM $schema.document_index_assets refs WHERE refs.asset_id=assets.asset_id)""",
            workspace,
            chunking,
            documentId,
        )
        jdbc.update(
            """DELETE FROM $schema.source_documents source WHERE workspace_id=? AND chunking_profile=? AND doc_id=?
            AND NOT EXISTS(SELECT 1 FROM $schema.document_index_metadata refs WHERE refs.workspace_id=source.workspace_id
                AND refs.chunking_profile=source.chunking_profile AND refs.doc_id=source.doc_id)""",
            workspace,
            chunking,
            documentId,
        )
    }

    private fun requireTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "Publication requires a transaction" }
    }
}
