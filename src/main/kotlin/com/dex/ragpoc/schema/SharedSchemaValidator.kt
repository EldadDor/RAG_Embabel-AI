package com.dex.ragpoc.schema

import com.dex.ragpoc.config.AppProperties
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

private val identifierPattern = Regex("^[a-z_][a-z0-9_]*$")

private val requiredVersions =
    setOf(
        "001_baseline",
        "002_workspace_authorization",
        "003_chunking_profiles",
        "004_document_assets",
        "005_model_profiles",
    )

data class SchemaModelProfile(
    val profileName: String,
    val dimensions: Int,
    val storageTarget: String,
    val status: String,
)

interface SchemaMetadataReader {
    fun missingRelations(relations: Set<String>): Set<String>

    fun appliedVersions(schema: String): Set<String>

    fun columnType(
        relation: String,
        column: String,
    ): String?

    fun modelProfile(
        schema: String,
        profileName: String,
    ): SchemaModelProfile?
}

class SharedSchemaValidationException(
    message: String,
) : IllegalStateException(message)

class SharedSchemaValidationService(
    private val metadata: SchemaMetadataReader,
    private val properties: AppProperties,
) {
    fun validate() {
        val schema = validatedIdentifier("PG_SCHEMA", properties.database.schema)
        val chunkTable = validatedIdentifier("PG_TABLE", properties.database.chunkTable)
        val relations =
            setOf(
                "$schema.schema_migrations",
                "$schema.$chunkTable",
                "$schema.source_documents",
                "$schema.conversation_turns",
                "$schema.conversation_summaries",
                "$schema.chat_sessions",
                "$schema.workspaces",
                "$schema.workspace_members",
                "$schema.document_assets",
                "$schema.chunk_assets",
                "$schema.model_profiles",
                "$schema.embedding_cache",
            )
        val missingRelations = metadata.missingRelations(relations)
        if (missingRelations.isNotEmpty()) {
            throw SharedSchemaValidationException(
                "PostgreSQL migrations are not applied; missing: ${missingRelations.sorted().joinToString()}. " +
                    "Apply the RAG-dev-plane migrations before starting Kotlin.",
            )
        }

        val missingVersions = requiredVersions - metadata.appliedVersions(schema)
        if (missingVersions.isNotEmpty()) {
            throw SharedSchemaValidationException(
                "PostgreSQL migrations are not applied; missing versions: ${missingVersions.sorted().joinToString()}.",
            )
        }

        val actualEmbeddingType = metadata.columnType("$schema.$chunkTable", "embedding")
        val expectedEmbeddingType = "vector(${properties.database.vectorDimension})"
        if (actualEmbeddingType != expectedEmbeddingType) {
            throw SharedSchemaValidationException(
                "PostgreSQL embedding column is ${actualEmbeddingType?.let { "'$it'" } ?: "missing"}; " +
                    "expected '$expectedEmbeddingType'. Update configuration or use the matching RAG-dev-plane profile.",
            )
        }

        val profileName = properties.rag.modelProfile
        val profile =
            metadata.modelProfile(schema, profileName)
                ?: throw SharedSchemaValidationException("PostgreSQL model profile '$profileName' is missing.")
        if (profile.status != "ready") {
            throw SharedSchemaValidationException("PostgreSQL model profile '$profileName' is '${profile.status}'; expected 'ready'.")
        }
        if (profile.dimensions != properties.database.vectorDimension) {
            throw SharedSchemaValidationException(
                "PostgreSQL model profile '$profileName' has ${profile.dimensions} dimensions; " +
                    "expected ${properties.database.vectorDimension}.",
            )
        }
        if (profile.storageTarget != chunkTable) {
            throw SharedSchemaValidationException(
                "PostgreSQL model profile '$profileName' targets '${profile.storageTarget}'; expected '$chunkTable'.",
            )
        }
    }

    private fun validatedIdentifier(
        name: String,
        value: String,
    ): String {
        if (!identifierPattern.matches(value)) {
            throw SharedSchemaValidationException("$name must be a lowercase PostgreSQL identifier; received '$value'.")
        }
        return value
    }
}

@Component
class JdbcSchemaMetadataReader(
    private val jdbcTemplate: JdbcTemplate,
) : SchemaMetadataReader {
    override fun missingRelations(relations: Set<String>): Set<String> =
        relations
            .asSequence()
            .filter { relation ->
                jdbcTemplate.queryForObject("SELECT to_regclass(?)", String::class.java, relation) == null
            }.toSortedSet()

    override fun appliedVersions(schema: String): Set<String> =
        jdbcTemplate
            .queryForList("SELECT version FROM $schema.schema_migrations", String::class.java)
            .filterNotNull()
            .toSet()

    override fun columnType(
        relation: String,
        column: String,
    ): String? =
        jdbcTemplate
            .query(
                """
                SELECT format_type(attribute.atttypid, attribute.atttypmod)
                FROM pg_attribute attribute
                WHERE attribute.attrelid = to_regclass(?)
                  AND attribute.attname = ?
                  AND NOT attribute.attisdropped
                """.trimIndent(),
                { resultSet, _ -> resultSet.getString(1) },
                relation,
                column,
            ).firstOrNull()

    override fun modelProfile(
        schema: String,
        profileName: String,
    ): SchemaModelProfile? =
        jdbcTemplate
            .query(
                "SELECT profile_name, dimensions, storage_target, status FROM $schema.model_profiles WHERE profile_name = ?",
                { resultSet, _ ->
                    SchemaModelProfile(
                        profileName = resultSet.getString("profile_name"),
                        dimensions = resultSet.getInt("dimensions"),
                        storageTarget = resultSet.getString("storage_target"),
                        status = resultSet.getString("status"),
                    )
                },
                profileName,
            ).firstOrNull()
}

@Component
class SharedSchemaValidator(
    metadata: JdbcSchemaMetadataReader,
    properties: AppProperties,
) : ApplicationRunner {
    private val validator = SharedSchemaValidationService(metadata, properties)

    override fun run(args: ApplicationArguments) {
        validator.validate()
    }
}
