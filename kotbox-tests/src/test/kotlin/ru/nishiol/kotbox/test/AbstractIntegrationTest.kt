package ru.nishiol.kotbox.test

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import ru.nishiol.kotbox.Kotbox
import ru.nishiol.kotbox.KotboxProperties
import ru.nishiol.kotbox.executeUpdate
import ru.nishiol.kotbox.jackson.JacksonPayloadSerializer
import ru.nishiol.kotbox.store.DefaultDialectProvider
import ru.nishiol.kotbox.store.KotboxStore
import ru.nishiol.kotbox.task.KotboxTask
import ru.nishiol.kotbox.transaction.SimpleTransactionManager
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractIntegrationTest(
    val shouldStartKotbox: Boolean,
    private val shouldCreateSchema: Boolean = true
) {
    protected val properties = KotboxProperties(
        table = KotboxProperties.Table(
            schema = "test",
            name = "kotbox_test"
        ),
        poll = KotboxProperties.Poll(
            batchSize = 10,
            sleepDuration = Duration.ofSeconds(1)
        )
    )

    protected val clock = TestClock(Instant.now(), ZoneOffset.UTC)

    protected val payloadSerializer = JacksonPayloadSerializer(jacksonObjectMapper())

    private val taskExecutorService = ThreadPoolExecutor(
        4,
        4,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(5),
        ThreadPoolExecutor.CallerRunsPolicy()
    )

    protected val kotboxUnknownTaskHandler = TestUnknownTaskHandler()

    private val dataSource by lazy {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = this@AbstractIntegrationTest.jdbcUrl
                username = this@AbstractIntegrationTest.jdbcUsername
                password = this@AbstractIntegrationTest.jdbcPassword
            }
        )
    }

    protected val transactionManager = SimpleTransactionManager { dataSource.connection }

    protected val kotboxStore = KotboxStore(DefaultDialectProvider(transactionManager), properties)

    private val tasks = mutableListOf<KotboxTask<*>>()
    protected val kotbox: Kotbox = Kotbox(
        tasksProvider = { tasks },
        payloadSerializer = payloadSerializer,
        kotboxStore = kotboxStore,
        transactionManager = transactionManager,
        kotboxUnknownTaskHandler = kotboxUnknownTaskHandler,
        taskExecutorService = taskExecutorService,
        clock = clock,
        properties = properties
    )

    protected val testTask = TestTask(kotbox).also {
        tasks += it
    }

    @BeforeEach
    fun setUp() {
        if (shouldStartKotbox) {
            kotbox.start()
        } else if (shouldCreateSchema) {
            transactionManager.doInNewTransaction {
                kotboxStore.createKotboxTableIfNotExists(it)
            }
        }
    }

    @AfterEach
    fun cleanUp() {
        kotbox.pause()
        clock.reset()
        testTask.reset()
        kotboxUnknownTaskHandler.reset()
        taskExecutorService.taskCount
        transactionManager.doInNewTransaction {
            it.connection.executeUpdate("TRUNCATE TABLE \"test\".\"kotbox_test\"")
        }
    }

    @AfterAll
    fun afterAll() {
        kotbox.shutdown()
        taskExecutorService.shutdown()
        dataSource.close()
    }

    abstract val jdbcUrl: String

    abstract val jdbcUsername: String

    abstract val jdbcPassword: String
}