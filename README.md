# Kotbox

**Kotbox** is a Kotlin library that implements the [**Transactional Outbox**](https://microservices.io/patterns/data/transactional-outbox.html) pattern for reliable event delivery in distributed systems. It ensures that database operations and outgoing tasks (messages or other side effects) remain in sync, providing eventual consistency without using distributed transactions. Kotbox is inspired by the Java library [gruelbox/transaction-outbox](https://github.com/gruelbox/transaction-outbox) and offers a Kotlin-first solution for transactional task scheduling and outbox processing.

Kotbox uses a relational database table as a durable queue to store pending tasks, ensuring that scheduled tasks are not lost and will eventually be executed. The library supports distributed processing (multiple application instances can share the load) and allows tasks to be grouped by optional **topics** to enforce ordered execution within each topic. Kotbox also provides configurable settings such as a task invisibility timeout (to temporarily hide a task while it is being processed), automatic retries, and retention policies for completed task records.

## Description

The **Transactional Outbox** pattern is used to avoid the problems of performing **dual writes** (e.g. saving to a database and sending a message to a message broker) in a single operation. Instead of relying on two-phase commits or risking inconsistencies, the pattern works by:

- **Storing the message/event in an outbox table** as part of the same database transaction that commits your business data.
- **Using a separate process to relay messages** from the outbox table to their destination (email service, message broker, another microservice, etc.) after the transaction commits.

With Kotbox, when you perform a transactional operation (for example, placing an order in the database), you can schedule a follow-up task (such as sending a confirmation email or publishing an event). Kotbox will only execute that task *after* the transaction successfully commits. If the transaction is rolled back, the scheduled outbox task is discarded. This ensures **atomicity**: your side effects (messages/events) are sent **if and only if** the corresponding database changes are saved.

**Key benefits of Kotbox:**

- **Reliable eventual consistency:** Guarantees that messages or tasks are not lost and will be delivered *at least once*. By using unique identifiers (idempotency keys) and idempotent task logic, you can achieve effectively exactly-once processing.
- **Atomic with business logic:** Outbox tasks are recorded within the same ACID transaction as your business data, so there's no window where a DB commit succeeds but the message send fails (or vice versa).
- **Decoupling & asynchrony:** Expensive or external actions (like sending emails, calling APIs, or publishing to Kafka) are moved out of the main request flow. They run asynchronously in the background, improving response times and decoupling services.
- **Kotlin-friendly API:** Define your tasks as Kotlin classes, with clear `execute` and error handling logic. Kotbox leverages Kotlin features and can integrate with Spring Boot or be used standalone in any Kotlin/JVM project.
- **Extensibility and flexibility:** Almost every aspect is configurable or pluggable – from how payloads are serialized, to which database/dialect is used for the outbox table, to custom retry policies per task.

In summary, Kotbox helps you build **resilient, distributed systems** where events, notifications, or cross-service messages are delivered reliably without compromising the consistency of your data.

## Delivery semantics

Kotbox guarantees **at-least-once delivery** of tasks by default. This means every scheduled task will be executed *at least* once, even in the presence of failures or crashes. However, tasks might be executed more than once in some scenarios (e.g., if a worker crashes during execution, retry is triggered).

For applications that require **exactly-once** effects, Kotbox alone cannot guarantee that out of the box – but you can achieve effectively exactly-once processing by making your tasks **idempotent**. In practice, this involves using idempotency keys and idempotent logic when scheduling and processing tasks:

- When calling `kotbox.schedule(...)`, provide a stable unique identifier (`idempotencyKey`) for the task. Kotbox uses this key to avoid inserting duplicate entries into the queue (if an entry with the same idempotency key already exists, the new schedule request is ignored).
- Inside your task's `KotboxTask.execute(...)` implementation, use the same identifier (for example, pass the idempotency key as part of the task payload or retrieve it from the `KotboxEntry` context) to ensure that if the task is executed multiple times, it does not produce duplicate side effects. For instance, check if an operation has already been performed for that key before performing it again.

By combining at-least-once delivery with idempotent task logic, you can achieve an **effectively exactly-once outcome** for each task's impact on your system.

## Installation

Kotbox is distributed as a set of modules so you can include only what you need:

- **Core library:** `kotbox-core` – the main logic and API.
- **Database dialects:** e.g. `kotbox-postgresql` – support for specific databases (PostgreSQL is currently provided).
- **Serialization:** `kotbox-jackson` – JSON serialization support using Jackson (optional, but needed to serialize/deserialize task payloads unless you implement your own).
- **Spring Boot Starter:** `kotbox-spring-boot-starter` – integration for Spring Boot (auto-configuration, bean setup).

**Gradle** (Kotlin DSL) example:
```kotlin
dependencies {
    implementation("ru.nishiol.kotbox:kotbox-core:0.9.0")
    implementation("ru.nishiol.kotbox:kotbox-postgresql:0.9.0")      // for PostgreSQL support
    implementation("ru.nishiol.kotbox:kotbox-jackson:0.9.0")         // for JSON serialization via Jackson
    implementation("ru.nishiol.kotbox:kotbox-spring-boot-starter:0.9.0") // for Spring Boot auto-configuration (if using Spring)
}
```

If you are using Maven, use the same coordinates for `<groupId>ru.nishiol.kotbox</groupId>` and the respective `<artifactId>` names.

> **Note:** The library is not published to Maven Central yet

> **Note:** Building Kotbox from source:
> 1. Clone the Kotbox repository and run `./gradlew publishToMavenLocal` to publish the artifacts to your local Maven repository.
> 2. In your project’s Gradle settings, add `mavenLocal()` to the repositories.
> 3. Then add the dependencies as shown above (ensuring the version matches the built version).

## Quick Start / Usage Example

This section demonstrates how to set up Kotbox and schedule a simple outbox task. We’ll show two scenarios: using Kotbox with Spring Boot (which requires minimal setup), and using Kotbox in a standalone Kotlin application.

### 1. Define an Outbox Task

First, define a task that you want to run after your business transaction commits. You create a class that implements `KotboxTask<P>` (where `P` is the payload type for the task) and provide an accompanying `KotboxTask.Definition`. The task’s `execute` method contains the logic to perform (for example, sending an email), and `onFailure` defines what to do if execution throws an exception.

For example, suppose we want to send a welcome email after a new user registers:

```kotlin
import ru.nishiol.kotbox.KotboxEntry
import ru.nishiol.kotbox.task.KotboxTask
import java.time.Instant

// Payload data for the email task
data class EmailPayload(val recipient: String, val body: String)

// Task implementation
class SendEmailTask : KotboxTask<EmailPayload> {
    // Define a unique ID and payload type for this task
    companion object : KotboxTask.Definition<EmailPayload> {
        override val id: String = "sendEmailTask"              // Unique task identifier
        override val payloadClass = EmailPayload::class        // Payload type for serialization
    }
    override val definition: KotboxTask.Definition<EmailPayload> = SendEmailTask

    override fun execute(payload: EmailPayload, topic: String?) {
        // This code runs asynchronously AFTER the transaction commits.
        // Perform the side effect, e.g., send an email:
        println("Sending welcome email to ${payload.recipient}: ${payload.body}")
        // (In real code, integrate with an email service or SMTP client here)
    }

    override fun onFailure(payload: EmailPayload, entry: KotboxEntry, cause: Throwable)
            : KotboxTask.OnFailureAction {
        // If sending fails, decide what to do. For example, retry after 1 minute:
        val nextTime = Instant.now().plusSeconds(60)
        return KotboxTask.OnFailureAction.Retry(atTime = nextTime)
        // You could also return Block or Ignore depending on the error and desired behavior.
    }
}
```

In the above example, `SendEmailTask` is our outbox task:
- It will send an email to `payload.recipient` with the given message body.
- If it fails (perhaps due to a network issue), we retry it after 60 seconds by returning `Retry(atTime = ...)`. Kotbox will reschedule the task for that future time. You could choose `OnFailureAction.Block` to stop retries and mark the task as `BLOCKED` (dead-letter), or `OnFailureAction.Ignore` to drop the task and mark it completed despite the failure.

> **Note:** The `id` in the companion object uniquely identifies the task type. This is stored in the outbox table so Kotbox knows which task class to invoke when processing. Make sure each distinct task type has a unique `id` string.

### 2. Configure Kotbox and Start the Outbox Processor

Next, set up the Kotbox engine and link it to your database and transaction system. Kotbox needs the following components to run:
- A `TransactionManager` to join or create transactions (so Kotbox knows when to commit its outbox entries).
- A `KotboxStore` (backed by your database) to persist and fetch outbox entries.
- A `PayloadSerializer` to serialize task payloads to JSON for storage (and deserialize them back when executing).
- The set of `KotboxTask` implementations (like `SendEmailTask`) that Kotbox can execute.
- Optionally, a custom `KotboxUnknownTaskHandler` to handle any outbox entries whose task type is not recognized (e.g., if code was updated and an old entry no longer matches a known task).

**Using Spring Boot:** If you have included `kotbox-spring-boot-starter` and have Spring’s transaction management set up, Kotbox will auto-configure most of these for you:
- It will detect your `DataSource` and `PlatformTransactionManager` and use them.
- It will create a `Kotbox` bean with a default thread pool, clock, and Jackson serializer (if Jackson is on the classpath).
- Any beans of type `KotboxTask` you have defined will be auto-registered as tasks.
- Kotbox will automatically start processing after the application context is refreshed.

*Minimal Spring Boot example:*

```kotlin
@Service
class UserService(val userRepository: UserRepository, val kotbox: Kotbox) {

    @Transactional
    fun registerUser(user: User) {
        // 1. Save user in the database (within a transaction)
        userRepository.save(user)
        // 2. Schedule the outbox task to send a welcome email after commit
        kotbox.schedule(SendEmailTask, EmailPayload(user.email, "Welcome, ${user.name}!"))
        // (The schedule call does not send the email immediately. It records an outbox entry to be processed later.)
    }
}
```

In this Spring Boot scenario:
- We mark the service method as `@Transactional`. The `kotbox.schedule(...)` call occurs inside the transaction. Kotbox will capture the `SendEmailTask` to be executed only if the surrounding transaction completes successfully.
- The `kotbox.schedule(TaskDefinition, payload)` method is used, where we pass the **companion object** of our task (`SendEmailTask`, which implements `KotboxTask.Definition`) and the payload. Kotbox automatically generates an **idempotency key** for this task (you can provide one to prevent duplicate scheduling of the same event).
- After the transaction commits, Kotbox’s background thread will pick up the new outbox entry and invoke `SendEmailTask.execute` asynchronously to actually send the email.
- Kotbox’s Spring Boot integration will also create the required database table on startup (by default) if it doesn’t exist, so you typically don’t need to manually create the outbox table for supported databases.

**Using Kotbox without Spring (Standalone):** If you are not using Spring Boot, you’ll need to configure Kotbox manually. This involves instantiating a `Kotbox` object with the necessary components. For example:

```kotlin
import ru.nishiol.kotbox.*
import ru.nishiol.kotbox.store.*
import ru.nishiol.kotbox.transaction.SimpleTransactionManager
import ru.nishiol.kotbox.task.KotboxUnknownTaskHandler
import java.time.Clock
import java.util.concurrent.Executors
import javax.sql.DataSource

// Suppose we have a DataSource for our database:
val dataSource: DataSource = /* initialize your DataSource (JDBC) */

// 1. Set up a transaction manager (using Kotbox's SimpleTransactionManager for JDBC)
val transactionManager = SimpleTransactionManager(dataSource)

// 2. Set up the Kotbox store (for PostgreSQL in this example)
val dialectProvider = DefaultDialectProvider(transactionManager) 
val kotboxStore = KotboxStore(dialectProvider, KotboxProperties()) 
// KotboxProperties() can be customized for table name, polling interval, etc. Here we use defaults.

// 3. Set up payload serializer (using Jackson for JSON serialization)
val objectMapper = 
    com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
val payloadSerializer = 
    ru.nishiol.kotbox.jackson.JacksonPayloadSerializer(objectMapper)

// 4. (Optional) Define how to handle unknown tasks or persistent failures
val unknownTaskHandler = KotboxUnknownTaskHandler { entry ->
    // We return an action for how to handle an unknown task entry.
    // For example, just block it (do nothing) so it can be investigated or retried manually:
    KotboxTask.OnFailureAction.Block
}

// 5. Instantiate Kotbox with our task(s) and configurations
val kotbox = Kotbox(
    tasksProvider = { listOf(SendEmailTask()) },  // provide instances of our KotboxTask(s)
    payloadSerializer = payloadSerializer,
    kotboxStore = kotboxStore,
    kotboxUnknownTaskHandler = unknownTaskHandler,
    transactionManager = transactionManager,
    clock = Clock.systemUTC(),
    taskExecutorService = Executors.newFixedThreadPool(4),  // thread pool for executing tasks (4 threads here)
    properties = KotboxProperties()  // default properties; can be tweaked
)

// 6. Start the Kotbox background processor
kotbox.start()
```

Once Kotbox is started, it will begin polling the database for new outbox entries and processing them in the background. Now you can use it in your code whenever you need to schedule an asynchronous task. For example:

```kotlin
// Begin a transaction (using the transaction manager or your framework's transaction)
transactionManager.doInNewTransaction { transactionContext ->
    // Obtain JDBC connection
    val connection = transactionContext.connection
    // ... do some database operations ...
    // Schedule an outbox task within the same transaction:
    kotbox.schedule(SendEmailTask, EmailPayload("user@example.com", "Hello!"))
    // ... you can schedule multiple tasks if needed ...
    // When this block exits, the transaction will commit and Kotbox will know to save the outbox entry.
}
// After commit, Kotbox's worker thread will eventually execute SendEmailTask with the provided payload.
```

In the standalone usage:
- We explicitly create a transaction (using `doInNewTransaction` provided by `SimpleTransactionManager`) and call `kotbox.schedule` inside it. The `schedule` method will defer the insertion of the outbox entry until just before commit.
- If the transaction rolls back (due to an exception), Kotbox will not insert the outbox entry at all.
- If the transaction commits, Kotbox inserts a row into the outbox table with status `PENDING`. The background thread (started by `kotbox.start()`) will find this row and process it.

That’s it! Kotbox takes care of reliably persisting the task and running it asynchronously. You can define as many task types as you need and schedule them as part of your transactional workflows.

## Architecture and extensibility

Kotbox’s design follows a modular architecture with clear separation of concerns, allowing you to customize or extend components as needed. Below is an overview of the key components and how they work together:

- **Outbox table (Persistent storage):** Kotbox uses a table in your relational database to store “outbox” entries. Each entry (represented by `KotboxEntry`) contains details such as a unique ID, an idempotency key, the task type identifier, the serialized payload, status, number of attempts, next execution time, etc. By default, the table schema is created automatically on startup (configurable). This table acts as a persistent queue of tasks that need to be processed.

- **KotboxTask:** You define tasks by implementing the `KotboxTask<P>` interface for your payload type `P`. Each task class has a companion object (or separate object) implementing `KotboxTask.Definition<P>` which provides a unique `id` and the payload class. At runtime, Kotbox keeps a registry of tasks by their `id`. When an entry in the outbox table is ready to process, Kotbox looks up the corresponding task and calls its `execute(payload, topic)` function. The task’s code typically performs an external action (send a message, call an API, etc.). If `execute` throws an exception, Kotbox catches it and calls your task’s `onFailure` to decide how to handle the failure (e.g., retry or give up).

- **Task topics and ordering:** When scheduling a task, you can optionally assign it a **topic** (logical group). Kotbox ensures that tasks sharing the same topic are processed in FIFO order one at a time, preserving order of events per topic. This is useful, for example, if tasks modify the same resource or if message ordering per key is important. Tasks without a topic or with different topics can be executed in parallel (Kotbox will fetch one task per topic at a time plus some ungrouped tasks to maximize throughput). Internally, Kotbox uses separate queries to fetch the next task from each topic and maintain ordering, while still allowing concurrency across different topics.

- **Idempotency:** Every time you schedule a task, Kotbox assigns an **idempotency key** (a unique UUID by default, or you can supply one). `KotboxEntry`’s equality and hashing are based on this key, ensuring that if you accidentally schedule the same logical task twice (with the same key), it will be treated as a duplicate and not inserted again. This helps prevent duplicate events. It’s up to you to provide a meaningful key when needed (for example, use an order ID for events related to that order), otherwise a random key guarantees uniqueness for each schedule call.

- **Transaction management:** The component `TransactionManager` (and `TransactionContext`) abstracts how Kotbox participates in your transactions. In a Spring environment, `SpringTransactionManager` ties into Spring’s `PlatformTransactionManager` and uses transaction synchronization callbacks to insert outbox entries *after* the main transaction commits. In a simpler setup, `SimpleTransactionManager` uses a JDBC connection to manage a transaction manually. The `TransactionContext` allows Kotbox to register actions to run before or after commit. For example, Kotbox registers an action to flush any pending outbox entries to the database upon a successful commit. This design ensures **outbox entries are only written on commit**, and never on rollback.

- **`KotboxStore` and `Dialect`s:** `KotboxStore` is responsible for all database interactions on the outbox table: inserting new entries, fetching pending entries for processing, updating their status/attempt counts, and removing or marking completed entries. It is designed to work with different SQL dialects and database features. A `Dialect` encapsulates DB-specific SQL. The `DefaultDialectProvider` will choose the appropriate `Dialect` based on your database at runtime. Currently, Kotbox provides support and tested dialect for **PostgreSQL** (versions 9.5+), including usage of features like `FOR UPDATE SKIP LOCKED` for efficient concurrent polling. Support for other databases (e.g., MySQL/MariaDB, Oracle, MS SQL) can be added by implementing new `Dialect`s – the architecture is flexible to accommodate that. If you have a custom storage mechanism entirely (say, writing to a different queue or NoSQL store), you could implement your own `KotboxStore`, though the library is primarily built around relational DBs.

- **Background processor (Worker):** When you call `kotbox.start()`, Kotbox launches a background polling thread. This thread wakes up periodically (or is signaled when new tasks are added) and checks the outbox table for any visible `PENDING` tasks that are due to be processed. It locks or marks each selected task to ensure that only one worker processes it at a time. Kotbox achieves this by immediately setting the task’s `nextAttemptTime` to a future timestamp (the current time plus a configurable *visibility timeout*), which makes the task **invisible** to other workers until that time. Then, Kotbox hands the task off to the **task executor thread pool**. The task executor (an `ExecutorService`) runs the `execute()` method of tasks, possibly in parallel threads (you can configure the thread pool size by supplying your own `ExecutorService`). By default, Kotbox’s Spring Boot starter configures a thread pool with a core size of 1 and a max size equal to the common fork-join pool parallelism (i.e., number of CPU cores), which is suitable for many cases. You can tune this if you expect to handle many outbox events concurrently.

- **Failure handling & retries:** Kotbox does not enforce a fixed retry policy. Instead, your task’s `onFailure` method decides how to handle exceptions. For example, you can instruct Kotbox to retry the task (schedule another attempt), mark it as **BLOCKED** (no further attempts), or ignore the failure (mark the task as completed). (See **Task processing flow and visibility timeout** below for more details on these options.)

- **Extensibility points:** Kotbox is built to be extensible:
    - *Custom serialization:* Implement the `PayloadSerializer` interface if you prefer a serialization mechanism other than Jackson/JSON (for instance, using Kotlinx Serialization or a binary format). You can then supply your serializer to Kotbox in the constructor or as a Spring bean.
    - *Database support:* As mentioned, you can add support for new databases by providing a custom `Dialect` and perhaps contributing it to `DefaultDialectProvider`. The KotboxStore will use those to generate SQL for your DB. The code is modular, so adding a new module like `kotbox-mysql` or `kotbox-oracle` with the appropriate SQL syntax is feasible.
    - *Transaction integration:* If you are in an environment with a different transaction management strategy, you can implement the `TransactionManager` interface to adapt Kotbox to it. (For example, if using a custom transaction mechanism or a framework like Micronaut or Quarkus, you could write an adapter similar to the Spring one.)
    - *Threading and scheduling:* You can provide your own `ExecutorService` to control how tasks are executed (single-threaded, a fixed pool, etc.). You can also tweak polling behavior (like how often Kotbox polls when idle, batch sizes for fetching tasks) via `KotboxProperties`.
    - *Unknown task handling:* Provide a custom `KotboxUnknownTaskHandler` to decide what to do with tasks that cannot be mapped to any known `KotboxTask` (e.g., log an error, send them to an external dead-letter queue, etc.).
    - *Multiple outbox processors:* While usually one `Kotbox` instance per application is enough, you could instantiate multiple Kotbox instances pointed at different tables or schemas if you needed to segregate certain tasks. (This is an uncommon scenario and would require careful configuration to avoid interference.)

## Integration with database and transactions

**Transactional consistency** is at the core of Kotbox. Here’s how Kotbox works with your database and transaction system to guarantee that outbox tasks are in sync with your data:

- **Enlisting in transactions:** When you call `kotbox.schedule(...)` inside a transaction, Kotbox does not immediately insert the outbox entry. Instead, it enlists in the on-going transaction. Only when your transaction is about to commit, Kotbox inserts the new outbox entry into the database **within the same transaction**. This means the insertion of the outbox row is atomic with your own data changes. If your transaction fails or is rolled back, Kotbox simply discards the pending task (nothing gets written to the outbox table).

- **Outbox table schema:** By default, Kotbox will use a table (configurable name, default `kotbox`) with columns such as:
    - `id` (auto-increment primary key)
    - `idempotency_key` (unique identifier for deduplication)
    - `version` (version for optimistic locking)
    - `task_definition_id` (the task type ID string)
    - `status` (e.g., PENDING, BLOCKED, PROCESSED)
    - `topic` (optional grouping key for ordering)
    - `creation_time` (timestamp of when scheduled)
    - `next_attempt_time` (timestamp when it should next run, updated on retry)
    - `last_attempt_time` (timestamp of last try)
    - `attempts` (count of how many times tried)
    - `payload` (the serialized payload data, typically JSON)

  Kotbox can automatically create this table on startup if it doesn’t exist (controlled by a setting `kotbox.table.createSchema`). This is convenient for development, though in production you might handle migrations yourself. Ensure the database user has rights to create tables if using auto-create. Otherwise, you can disable auto-creation and use a provided SQL script or DDL statement from Kotbox’s documentation to set up the table.

- **Transaction scope and isolation:** It’s important that the outbox table reside in the **same database (or transactional resource)** as the main data you are modifying. The pattern assumes a single transaction can cover both. Kotbox does **not** support coordinating multiple different transactional resources (it’s not a replacement for distributed transactions; rather it avoids them by using one resource). Use Kotbox when your business data and outbox can share a database transaction (common in microservice architectures where each service has its own database).

- **Database support and locking:** Currently, Kotbox fully supports relational databases that provide standard SQL and locking mechanisms. PostgreSQL is thoroughly supported (Kotbox uses `SELECT ... FOR UPDATE SKIP LOCKED` to handle concurrent polling in multiple instances efficiently). Other databases that support similar features (e.g., MySQL 8+, Oracle 12c+) are planned or in progress.

- **Multiple application instances:** In a distributed environment with multiple instances of your application (horizontal scaling), all instances can run Kotbox pointing to the same outbox table. Kotbox is designed to work in such a scenario — each instance will attempt to poll for pending tasks. Thanks to database-level coordination, a task will be claimed by one instance for processing and others will skip it. This provides **high availability**: if one instance goes down, another can continue processing new outbox events, and any task in progress on the failed instance will become available for processing once its visibility timeout expires. You don’t need external coordination; the database ensures only one transaction can lock and update a task at a time.

- **Ordering guarantees:** If your use case requires that messages/events are sent in the exact order of the transactions that produced them, Kotbox can enforce this via topics (as described in Architecture). All tasks in a given topic are processed sequentially. If strict ordering across the entire outbox is needed (less common), you might use a single topic for all tasks or design your system so that ordering only matters within logical partitions (which is what topics achieve).

- **Transaction context in Tasks:** Kotbox executes tasks in a new transaction, **outside** of the original transaction that scheduled the task. You should be careful to treat tasks as separate from the original business transaction. The whole idea is to decouple them: the original transaction has completed, and now the task runs independently (though you can certainly use database operations inside a task if needed, just be aware they are new transactions).

- **Environmental requirements and limitations:**
    - Your application needs to be able to run a background thread or scheduled task. In restrictive environments (like serverless functions or certain PaaS that disallow background threads), Kotbox might not be suitable unless you have a long-running component to run the outbox processor.
    - Time synchronization: Kotbox uses server time (via `Clock.systemUTC()` by default) to schedule future retries (`atTime`). If you run multiple instances, ensure their clocks are reasonably in sync (within a few seconds) so that scheduling delays behave consistently. Alternatively, consider using the database time (you could customize Kotbox to use an SQL current timestamp for scheduling).
    - If the application crashes or is shut down while a task is in progress, Kotbox’s at-least-once guarantee ensures the task will not be lost. The task remains in the outbox with status `PENDING` and its `nextAttemptTime` already set in the future (due to the visibility timeout). It will become visible for processing again once that timestamp is reached, allowing another instance (or the restarted application) to pick it up. However, because Kotbox cannot know whether the task might have actually completed just before the crash, it is possible the task will execute twice. To handle this, make sure your task logic is **idempotent** so that a duplicate execution does not cause unintended effects.
    - Ensure that your transaction boundaries are well-defined. Only call `kotbox.schedule` *within* a transaction. Calling it outside a transaction will cause Kotbox to throw an error (since it requires a current transaction to enlist in). If you need to schedule something outside of an existing transaction context, you should explicitly create a transaction around it or use `TransactionManager.doInNewTransaction` as shown in the standalone example.

## Task processing flow and visibility timeout

Kotbox continuously polls the database for tasks that are due to be executed. When a Kotbox worker picks up a task, it marks that task as "in progress" by updating its *visibility timeout*. Specifically, the task entry remains in status `PENDING` but its `nextAttemptTime` is immediately set to a future timestamp (the current time plus a configurable visibility timeout). Such a task is considered **invisible** to other Kotbox workers, meaning it will not be picked up by another instance while one worker is processing it. In other words, any entry with `status = PENDING` and `nextAttemptTime > now` is treated as invisible (temporarily hidden from further polling).

After a worker successfully executes a task to completion, Kotbox marks the task as **PROCESSED** and updates its `nextAttemptTime` to a time in the future based on the retention policy. This ensures the task won't be picked up again and that it will be cleaned up after the retention period elapses.

If a task execution fails or throws an exception, Kotbox will invoke the task's failure handler (`KotboxTask.onFailure`). Depending on the `OnFailureAction` returned by your task, Kotbox will take one of the following actions:

- **Retry** – The task's status remains PENDING, and `nextAttemptTime` is set to the future time specified (by the `Retry` action). This makes the task invisible until that time, after which it becomes visible again for another attempt.
- **Block** – The task is marked as **BLOCKED**. This means no further attempts will be made (the task will not be retried).
- **Ignore** – The task is marked as PROCESSED (essentially skipped/ignored after the failure). It will not be retried and will be treated as completed.

## Handling Blocked Tasks

Kotbox can mark a task as **BLOCKED** if its execution repeatedly fails and the task’s `onFailure` handler returns a `Block` action. A BLOCKED task is essentially a "dead-letter" entry – Kotbox will not make further attempts to run it. These tasks remain in the outbox table (with status `BLOCKED`) and require manual intervention or custom handling. Below are some strategies for dealing with tasks in the BLOCKED state:

- **Logging & monitoring:** You may log an event whenever a task is marked as BLOCKED. Ensure that these logs are monitored. Consider implementing application metrics to count or flag BLOCKED tasks so that they can be tracked over time.
- **Alerting:** Treat a BLOCKED task as an incident that might need attention. Set up alerts (for example, in your monitoring system) to notify your team when a task enters the BLOCKED state.
- **Manual retry (unblocking):** After investigating and resolving the root cause of a failure, you may want to retry a BLOCKED task. Kotbox provides a helper method `kotbox.unblock(entryId)` that resets a blocked task back to **PENDING** and schedules it for processing again. Use this to manually re-queue tasks once it's safe to do so (for instance, from an admin console or maintenance job).
- **Dead-letter queueing:** If your system requires special handling of failed tasks, consider moving BLOCKED task details to a dedicated dead-letter queue or store. For example, you could build a small process that periodically scans the outbox table for BLOCKED entries and publishes their information to an external queue or sends notifications. This allows you to triage or process these tasks outside of Kotbox’s normal flow.
- **Introspection & cleanup:** To diagnose why a task failed, you might inspect the task’s payload and any logged exception stack traces. The outbox table can be queried directly (e.g., `SELECT * FROM kotbox_table WHERE status = 'BLOCKED'`) to find BLOCKED entries. Once a decision is made (retry or permanently ignore), you can either unblock the task for another attempt or remove/mark it as processed (if the task should be abandoned).

## Handling instance crashes

Kotbox is designed to handle crashes or restarts of application instances without losing tasks. If an instance crashes **after starting a task but before the task's result is committed**, the task will remain in the queue with `status = PENDING` and its previously set `nextAttemptTime` (which will likely still be in the future due to the visibility timeout). In this scenario, the task entry simply stays in the database untouched until its visibility timeout expires. Once the `nextAttemptTime` timestamp is reached (i.e., the current time has caught up or passed it), the task becomes visible to be polled again. At that point, another running instance (or the restarted application) can pick up and execute the task.

This behavior ensures that even if a worker node goes down mid-processing, the task is not lost — it will eventually be processed again on a surviving or new instance.

**Note:** It's important to design your task logic to handle the possibility of duplicate execution. A task that was interrupted by a crash may be executed again, so if the task interacts with external systems or databases, make sure to guard against applying the same changes twice (for example, by using the idempotency patterns discussed above).

## Configuration

Kotbox provides several configuration properties to customize its behavior. If you are using the Spring Boot Starter, these properties can be set in your **application.yml** (or application.properties) file under the `kotbox` prefix. Below is a list of all available configuration settings, their default values, and a brief description. Each property includes an example of how it can be configured in YAML:

- **`kotbox.enabled`** (default: `true`) – Master enable/disable flag for Kotbox. If set to `false`, Kotbox's auto-configuration is turned off and the Kotbox engine will not start at runtime.  
  *Example (YAML):*
  ```yaml
  kotbox:
    enabled: false
  ```
- **`kotbox.autoStart`** (default: `true`) – Controls whether Kotbox starts automatically when the Spring application context is refreshed. When `true`, Kotbox begins polling for tasks as soon as the application is up. If set to `false`, you will need to start Kotbox manually by calling `kotbox.start()` in your code at the appropriate time.  
  *Example (YAML):*
  ```yaml
  kotbox:
    autoStart: false
  ```
- **`kotbox.table.name`** (default: `"kotbox"`) – The name of the database table that Kotbox uses to store tasks. You can change this if you want to use a custom table name for the task queue.  
  *Example (YAML):*
  ```yaml
  kotbox:
    table:
      name: my_tasks_queue
  ```
- **`kotbox.table.schema`** (default: `"public"`) – The database schema where the Kotbox table is located. Set this if you need to place the Kotbox table in a schema other than the default.  
  *Example (YAML):*
  ```yaml
  kotbox:
    table:
      schema: custom_schema
  ```
- **`kotbox.table.createSchema`** (default: `true`) – Whether Kotbox should automatically create the table (and its indexes) on startup if it does not exist. When this is `true`, Kotbox will execute the necessary DDL statements to ensure the table is present. If your environment requires manually creating database structures or you lack permissions for DDL, you may set this to `false`.  
  *Example (YAML):*
  ```yaml
  kotbox:
    table:
      createSchema: false
  ```
- **`kotbox.poll.batchSize`** (default: `4096`) – The maximum number of task entries Kotbox will fetch from the database in a single polling query. Increasing this value allows Kotbox to retrieve and process more tasks per batch (improving throughput if you have many tasks), but also means each poll query and transaction will be heavier. Lower this value if you prefer smaller batches or have memory concerns.  
  *Example (YAML):*
  ```yaml
  kotbox:
    poll:
      batchSize: 1000
  ```
- **`kotbox.poll.sleepDuration`** (default: `1m` i.e. one minute) – The duration Kotbox will wait (sleep) between polling cycles when no new tasks are found or after processing a batch. Essentially, if Kotbox finds the queue empty, it will pause for this duration before checking the database again. You can shorten this interval for lower latency (finding new tasks faster) at the cost of more frequent database polling, or lengthen it to reduce database load.  
  *Example (YAML):*
  ```yaml
  kotbox:
    poll:
      sleepDuration: 30s
  ```
- **`kotbox.retentionDuration`** (default: `7d` i.e. seven days) – How long a processed task entry is retained in the database before being permanently deleted. After a task is marked PROCESSED, its `nextAttemptTime` is set to the current time plus the retention duration. Kotbox will periodically remove (purge) processed entries whose retention time has passed, preventing the table from growing indefinitely. Adjust this value if you need to keep completed task records for more or less time (e.g., for debugging or audit purposes).  
  *Example (YAML):*
  ```yaml
  kotbox:
    retentionDuration: 14d
  ```
- **`kotbox.entryVisibilityTimeout`** (default: `1m` i.e. one minute) – The invisibility timeout for a task entry that has been fetched for processing. When a task is taken by a worker, its `nextAttemptTime` is set to now plus this timeout, making it invisible to other workers for that duration. If the task is not completed and marked processed before this timeout expires (for example, if the processing node crashes or the task is still running), the entry will become visible again after the timeout so that it can be retried. You should configure this to a value that is longer than the expected maximum processing time of your tasks. For instance, if tasks usually finish within a few seconds, a 1 minute timeout is sufficient; if some tasks might take 5 minutes, consider setting a larger timeout to avoid premature retries.  
  *Example (YAML):*
  ```yaml
  kotbox:
    entryVisibilityTimeout: 5m
  ```

## Future plans

Kotbox is an evolving project, and there are several enhancements and features planned for the future:

- **Additional database `Dialect`s:** Expanding support to other major relational databases is a high priority. MySQL/MariaDB support is planned next, followed by dialects for Oracle and SQL Server. This will allow Kotbox to be used in a wider range of environments. Contributions for dialect implementations are welcome.

- **Enhanced failure recovery:** In the current design, tasks that consistently fail can be left in a `BLOCKED` state (dead-letter). Future versions may introduce built-in **dead-letter queue handling** or automatic surfacing of blocked tasks for monitoring. For instance, there could be configuration for automatically unblocking tasks that have been blocked for a certain time or sending metrics/logs for blocked entries to make operations easier.

- **Automatic retries configurations:** While `KotboxTask.onFailure` gives fine-grained control, there are plans to offer some convenient utilities or base classes for common retry strategies (e.g., exponential backoff, max attempts). This could reduce boilerplate for tasks that just want “retry up to N times with X delay” without custom code each time.

- **Metrics and monitoring:** Integrating with monitoring systems (Micrometer metrics, etc.) is on the roadmap. This would allow exporting the number of pending/blocked tasks, processing rates, execution latencies, etc., to tools like Prometheus or CloudWatch, giving better visibility into the outbox processing pipeline.

- **Testing utilities:** The project aims to provide a testing mode or utilities to make it easier to test code that uses Kotbox. For example, one idea is to run Kotbox in a "synchronous" or stubbed mode where scheduled tasks execute immediately (for unit tests), or provide helpers to assert that certain tasks were scheduled.

- **API stabilization:** Plans include polishing the APIs and possibly introducing a builder or DSL to construct Kotbox instances more easily for non-Spring use cases. Any breaking changes will be made before a 1.0 release, to ensure stability thereafter.

Feedback and contributions are welcome. If you have ideas for improvement or encounter issues, please open an issue or pull request on the project’s repository.

## License

Kotbox is open-source software licensed under the **MIT License**. This means you are free to use, modify, and distribute it in your own projects. See the [LICENSE](./LICENSE) file for the full license text.

<hr/>

*Happy coding! By leveraging Kotbox and the Transactional Outbox pattern, you can build more robust microservices that confidently handle asynchronous workflows without losing data or consistency.*
