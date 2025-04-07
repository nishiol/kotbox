package ru.nishiol.kotbox.test.postgresql

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import ru.nishiol.kotbox.test.KotboxStoreTests

@Testcontainers
class Postgresql16KotboxStoreTests : KotboxStoreTests() {
    companion object {
        @Container
        @JvmStatic
        val postgresql = PostgreSQLContainer("postgres:16-alpine")
    }

    override val jdbcUrl: String get() = postgresql.jdbcUrl
    override val jdbcUsername: String get() = postgresql.username
    override val jdbcPassword: String get() = postgresql.password
}