package ru.nishiol.kotbox.spring

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.ObjectFactory
import org.springframework.beans.factory.config.CustomScopeConfigurer
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureOrder
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionScope
import ru.nishiol.kotbox.*
import ru.nishiol.kotbox.jackson.JacksonPayloadSerializer
import ru.nishiol.kotbox.spring.transaction.SpringTransactionContext
import ru.nishiol.kotbox.spring.transaction.SpringTransactionManager
import ru.nishiol.kotbox.store.DefaultDialectProvider
import ru.nishiol.kotbox.store.DialectProvider
import ru.nishiol.kotbox.store.KotboxStore
import ru.nishiol.kotbox.task.KotboxTask
import ru.nishiol.kotbox.task.KotboxUnknownTaskHandler
import ru.nishiol.kotbox.transaction.TransactionManager
import java.time.Clock
import java.util.*
import java.util.concurrent.*
import javax.sql.DataSource
import kotlin.math.max


@AutoConfiguration
@AutoConfigureOrder
@ConditionalOnProperty(value = ["kotbox.enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(value = [PlatformTransactionManager::class, DataSource::class])
@EnableConfigurationProperties(KotboxConfigurationProperties::class)
class KotboxConfiguration {

    @Bean(destroyMethod = "shutdown")
    fun kotbox(
        tasksProvider: ObjectFactory<Optional<List<KotboxTask<*>>>>,
        kotboxStore: KotboxStore,
        kotboxUnknownTaskHandler: KotboxUnknownTaskHandler,
        kotboxClock: Clock,
        kotboxTaskExecutorService: ExecutorService,
        kotboxTransactionManager: SpringTransactionManager,
        kotboxPayloadSerializer: PayloadSerializer,
        configurationProperties: KotboxConfigurationProperties
    ): Kotbox = Kotbox(
        tasksProvider = { tasksProvider.`object`.orElse(emptyList()) },
        payloadSerializer = kotboxPayloadSerializer,
        kotboxStore = kotboxStore,
        kotboxUnknownTaskHandler = kotboxUnknownTaskHandler,
        transactionManager = kotboxTransactionManager,
        clock = kotboxClock,
        taskExecutorService = kotboxTaskExecutorService,
        properties = configurationProperties.toKotboxProperties()
    )

    @Bean
    fun kotboxStore(
        kotboxDialectProvider: DialectProvider,
        configurationProperties: KotboxConfigurationProperties
    ): KotboxStore =
        KotboxStore(kotboxDialectProvider, configurationProperties.toKotboxProperties())

    @Bean
    fun kotboxTransactionContextScopeConfigurer(): CustomScopeConfigurer = CustomScopeConfigurer().apply {
        addScope("kotboxTransactionContext", SimpleTransactionScope())
    }

    @Bean
    fun kotboxDialectProvider(transactionManager: TransactionManager): DefaultDialectProvider =
        DefaultDialectProvider(transactionManager)

    @Bean
    @Scope("kotboxTransactionContext")
    fun springTransactionContext(
        dataSource: DataSource
    ): SpringTransactionContext = SpringTransactionContext(dataSource)

    @Bean
    fun springTransactionManager(
        springTransactionContextObjectFactory: ObjectFactory<SpringTransactionContext>
    ): SpringTransactionManager = SpringTransactionManager(springTransactionContextObjectFactory)

    @Bean
    @ConditionalOnMissingBean(name = ["kotboxKotboxUnknownTaskHandler"])
    fun kotboxUnknownTaskHandler(): KotboxUnknownTaskHandler =
        KotboxUnknownTaskHandler { KotboxTask.OnFailureAction.Block }

    @Bean
    @ConditionalOnMissingBean(name = ["kotboxClock"])
    fun kotboxClock(): Clock = Clock.systemUTC()

    @Bean
    @ConditionalOnMissingBean(name = ["kotboxTaskExecutorService"])
    fun kotboxTaskExecutorService(): ExecutorService = ThreadPoolExecutor(
        /* corePoolSize = */ 1,
        /* maximumPoolSize = */ max(1, ForkJoinPool.commonPool().parallelism),
        /* keepAliveTime = */ 0,
        /* unit = */ TimeUnit.MILLISECONDS,
        /* workQueue = */ ArrayBlockingQueue(16384),
        /* threadFactory = */ KotboxWorkerThreadFactory(),
        /* handler = */ ThreadPoolExecutor.CallerRunsPolicy()
    )

    @Bean
    fun kotboxContextRefreshedEventListener(
        kotbox: Kotbox,
        kotboxConfigurationProperties: KotboxConfigurationProperties
    ): KotboxContextRefreshedEventListener = KotboxContextRefreshedEventListener(
        kotbox = kotbox,
        configurationProperties = kotboxConfigurationProperties
    )

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JacksonPayloadSerializer::class)
    @ConditionalOnMissingBean(name = ["kotboxPayloadSerializer"])
    protected class JacksonPayloadSerializerConfiguration {
        @Bean
        fun kotboxJacksonPayloadSerializer(
            kotboxObjectMapper: ObjectMapper
        ): JacksonPayloadSerializer = JacksonPayloadSerializer(kotboxObjectMapper)

        @Bean
        @ConditionalOnMissingBean(name = ["kotboxObjectMapper"])
        fun kotboxObjectMapper(): ObjectMapper = ObjectMapper().findAndRegisterModules()
    }
}