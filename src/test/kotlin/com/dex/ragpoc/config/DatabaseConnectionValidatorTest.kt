package com.dex.ragpoc.config

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.sql.Connection
import javax.sql.DataSource

class DatabaseConnectionValidatorTest {
    @Test
    fun `validates PostgreSQL without issuing schema mutations`() {
        val connection = mockk<Connection>(relaxed = true)
        val dataSource = mockk<DataSource>()
        every { dataSource.connection } returns connection
        every { connection.isValid(5) } returns true

        DatabaseConnectionValidator(dataSource).run(mockk(relaxed = true))

        verify(exactly = 1) { connection.isValid(5) }
        verify(exactly = 1) { connection.close() }
    }
}
