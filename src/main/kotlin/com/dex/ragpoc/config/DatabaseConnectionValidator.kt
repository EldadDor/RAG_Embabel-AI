package com.dex.ragpoc.config

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import javax.sql.DataSource

@Component
class DatabaseConnectionValidator(
    private val dataSource: DataSource,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        dataSource.connection.use { connection ->
            check(connection.isValid(5)) { "PostgreSQL connection validation failed" }
        }
    }
}
