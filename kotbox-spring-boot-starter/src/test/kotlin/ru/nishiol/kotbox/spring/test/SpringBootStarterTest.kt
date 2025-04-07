package ru.nishiol.kotbox.spring.test

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import ru.nishiol.kotbox.Kotbox
import ru.nishiol.kotbox.KotboxEntry
import ru.nishiol.kotbox.task.KotboxTask
import java.time.Duration
import java.util.*
import kotlin.reflect.KClass

@SpringBootApplication
@SpringBootTest
@Testcontainers
class SpringBootStarterTest {
    @Autowired
    private lateinit var kotboxTestTask: KotboxTestTask

    @Autowired
    private lateinit var testService: TestService

    class KotboxTestTask : KotboxTask<KotboxTestTask.Payload> {
        data class Payload(val id: UUID)

        companion object : KotboxTask.Definition<Payload> {
            override val id: String = "KotboxTestTask"
            override val payloadClass: KClass<Payload> = Payload::class
        }

        override val definition: KotboxTask.Definition<Payload> = KotboxTestTask

        override fun onFailure(payload: Payload, entry: KotboxEntry, cause: Throwable): KotboxTask.OnFailureAction =
            KotboxTask.OnFailureAction.Ignore

        var result: Payload? = null
            private set

        override fun execute(payload: Payload, topic: String?) {
            result = payload
        }
    }

    open class TestService(private val kotbox: Kotbox) {
        @Transactional
        open fun scheduleTask(payload: KotboxTestTask.Payload) {
            kotbox.schedule(KotboxTestTask, payload)
        }
    }

    @Test
    fun `schedule and execute task`() {
        // given
        val payload = KotboxTestTask.Payload(UUID.randomUUID())

        // when
        testService.scheduleTask(payload)

        // then
        await.atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(kotboxTestTask.result).isEqualTo(payload) }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Configuration {
        @Bean
        fun kotboxTestTask(): KotboxTestTask = KotboxTestTask()

        @Bean
        fun testService(kotbox: Kotbox): TestService = TestService(kotbox)
    }

    companion object {
        @Container
        @JvmStatic
        val postgresql = PostgreSQLContainer("postgres:17-alpine")

        @Suppress("unused")
        @DynamicPropertySource
        @JvmStatic
        fun injectProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgresql.jdbcUrl }
            registry.add("spring.datasource.username") { postgresql.username }
            registry.add("spring.datasource.password") { postgresql.password }
        }
    }
}