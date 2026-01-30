# Factory Machine Event Backend System
This project demonstrates how a real-world backend system ingests machine events, handles duplicates and concurrency safely, and exposes meaningful analytics using clean, explainable design.

This README walks through the system end-to-end, explaining what the system does, how it is designed, and why each major architectural and implementation decision was made.

##  Project Overview (What & Why)

### What this system does
This is a high-performance backend system designed to ingest, process, and analyze **machine events** from a factory floor. Machines send event data (timestamps, duration, defect counts) in batches. The system processes these events to ensure data integrity and provides APIs to query statistics like "top failing lines" or "machine health."

### The Real-World Problem
In a factory, thousands of machines emit telemetry data. This data is often:
*   **Noisy:** Machines might send the same event multiple times (retries).
*   **Out of Order:** Network lag can cause events to arrive late.
*   **Concurrent:** Thousands of machines send data simultaneously.
*   **Corrected:** A machine might send an update to a previous event (e.g., updating accurate defect counts).

### Why this is NOT a simple CRUD app
A simple "Create, Read, Update, Delete" app would fail here because:
1.  **Concurrency:** Multiple threads might try to process the same machine's data at the same millisecond. Without protection, we get race conditions.
2.  **Deduplication:** We must detect if an event is a duplicate or a valid update based on complex business logic (timestamps, payload equality).
3.  **Performance:** The system must handle **1000 events in < 1 second**. Standard row-by-row insertion is too slow.

### Technologies
*   **Java 17 & Spring Boot 3:** Robust, enterprise-standard framework for building scalable backends.
*   **MySQL:** Chosen for ACID compliance. We need strict consistency (transactions) to ensure no data is lost or corrupted during concurrent updates.
*   **Spring Data JPA (Hibernate):** Simplifies database interactions but requires careful tuning (batching, indexes) for performance.
*   **Lombok:** Reduces boilerplate code (Getters, Setters, Builders).

---

## System Architecture (Big Picture)

The system follows a classic **Layered Architecture**. This ensures separation of concerns, making the code testable and maintainable.

### High-Level Flow
```text
[Machines] 
    ⬇ (HTTP POST Batch)
[Controller Layer] -> Validates Request Format
    ⬇
[Service Layer] -> Business Logic (Validations, Deduplication, Retry Loop)
    ⬇
[Repository Layer] -> Database Queries (JPA/Hibernate)
    ⬇
[MySQL Database] -> Persistent Storage (ACID Transactions)
```

### Explanation of Layers
1. **Controller Layer (`EventController`, `StatsController`):**
   *   **Role:** The "Doorway" to the system. It handles HTTP requests, deserializes JSON, and delegates work. It does *not* contain business logic.
   *   **Why:** We can change the API transport (e.g., to gRPC) without touching the core logic.

2. **Service Layer (`EventIngestionService`, `StatsService`):**
   *   **Role:** The "Brain". It handles the complex rules: strict validation, thread-safe deduplication, and calculating statistics.
   *   **Key Design:** Uses `@Transactional` to ensure a batch of operations either all succeed or all fail (atomicity).

3. **Repository Layer (`MachineEventRepository`):**
   *   **Role:** The "Storage Interface". Abstraction over the database.
   *   **Why:** We can write complex SQL queries here (like aggregation for stats) and keep them separate from Java logic.

---

## API Layer (Controllers Deep Dive)

We expose a RESTful API. REST was chosen for its universality and ease of debugging.

### 1. Ingestion API
*   **Endpoint:** `POST /events/batch`
*   **Purpose:** Accepts a list of events from machines.
*   **Design Philosophy:**
    *   **Batching:** Machines send multiple events at once to reduce network overhead (1 call vs 100 calls).
    *   **Response:** Returns a distinct status for *each* event (Accepted, Deduped, Updated, Failed). This "Partial Success" model is crucial for distributed systems; we don't want to fail an entire batch just because one event is bad.

### 2. Query/Stats APIs
*   **Endpoint:** `GET /stats?machineId=...&start=...&end=...`
    *   **Purpose:** Detailed health check for a specific machine. Returns calculated availability and defect rates.
*   **Endpoint:** `GET /stats/top-defect-lines`
    *   **Purpose:** Dashboard connectivity. Quickly identifies which production lines are failing the most.

---

## Database Design (Deep Dive)

We use a **Relational Database (MySQL)** because the data is highly structured and requires strict consistency (ACID).

### Entity: `MachineEvent` (Table: `machine_events`)

| Column | Type | Purpose |
| :--- | :--- | :--- |
| `eventId` | VARCHAR (PK) | **Primary Key**. Unique UUID generated by the machine. Guarantees idempotency. |
| `machineId` | VARCHAR | Identifies the source machine. |
| `eventTime` | TIMESTAMP | When the event *actually happened* (Machine Time). |
| `receivedTime`| TIMESTAMP | When the event *arrived at our server*. Critical for resolving conflicts (Latest Wins). |
| `durationMs` | BIGINT | How long the machine cycle took. |
| `defectCount`| INT | Number of defects produced. `-1` indicates unknown. |
| `version` | BIGINT | **Optimistic Locking**. Internal field used by Hibernate to detect concurrent writes. |

### Indexing Strategy
*   **Primary Key (`eventId`):** Fast lookups for deduplication. Clustered index by default.
*   **Composite Index (`machineId`, `eventTime`):**
    *   Used by: `GET /stats`
    *   **Why:** Querying "Machine A between 9:00 and 10:00" needs to filter by machine *first*, then range scan on time.
*   **Single Index (`eventTime`):**
    *   Used by: `GET /stats/top-defect-lines` (global time range queries).

---

## Deduplication & Update Logic (Core Logic)

This is the most critical part of the system. We ensure data correctness through a "Latest Writer Wins" strategy combined with "Payload Comparison".

**The Algorithm (`EventIngestionService.attemptUpsert`):**

1.  **Check Existence:** Query DB by `eventId`.
2.  **Scenario A: New Event (Not found)**
    *   Action: INSERT.
    *   Result: `ACCEPTED`.
3.  **Scenario B: Existing Event Found**
    *   **Sub-check:** Is `incoming.receivedTime` > `existing.receivedTime`?
    *   **NO (Stale):** The incoming event is older than what we have. Ignore it.
        *   Result: `DEDUPED`.
    *   **YES (Newer):** We consider updating.
        *   **Payload Check:** Are business fields (`duration`, `defects`, etc.) identical?
        *   **YES:** It's just a duplicate retry. Action: None. Result: `DEDUPED`.
        *   **NO:** It's a valid correction. Action: UPDATE fields. Result: `UPDATED`.

**Why this is safe:** It handles network reordering. If an Update arrives *before* the Original, the Original will be rejected later as "Stale".

---

## Thread-Safety Strategy

The system must handle 10 concurrent requests for the same `eventId` without corruption.

### Challenge
If Thread A reads an event, and Thread B reads the same event, both modify it and save, one update effectively "overwrites" the other blindly (Lost Update Problem).

### Solution: Optimistic Locking + Retries
1.  **Optimistic Locking:** We added a `@Version` field to the Entity.
    *   When Thread A saves, it checks: `UPDATE ... SET version = 2 WHERE id = X AND version = 1`.
    *   If Thread B already updated it to version 2, Thread A's update fails (0 rows modified).
    *   JPA throws `OptimisticLockingFailureException`.
2.  **Retry Loop:**
    *   The Service wraps the logic in a loop (Max 3 attempts).
    *   If an optimistic lock failure occurs, we **re-read** the latest data from DB and try again.

**Why not `synchronized` or Pessimistic Locking?**
*   `synchronized`: Only works on one server instance. Fails in a distributed cluster.
*   Pessimistic Locking (`SELECT ... FOR UPDATE`): Locks database rows, causing massive performance bottlenecks at scale.
*   **Optimistic Locking is non-blocking and highly performant for this read-heavy/write-many workload.**

---

## Performance Optimizations

To meet the "1000 events < 1 second" requirement:

1.  **Batch Ingestion:** We process lists of events, not one-by-one HTTP calls.
2.  **Efficient Deduplication:** We only update if the payload *actually changed*. This saves expensive database write operations.
3.  **Indexing:** Indices allow the stats queries to run in milliseconds even with millions of rows.
4.  **Connection Pooling:** Spring Boot's default HikariCP manages DB connections efficiently, avoiding the overhead of opening/closing sockets.

---

## Validation Rules

We aggressively reject invalid data to keep the database clean.

1.  **Future Events:** `eventTime > Now + 15 mins` -> REJECT.
    *   *Why:* Prevents clocks on machines from being wildly set to the wrong year, which would mess up stats.
2.  **Invalid Duration:** `duration < 0` or `> 6 hours` -> REJECT.
    *   *Why:* A machine cycle can't be negative. 6 hours is a sanity check for a single cycle.
3.  **Defect Count -1:** Allowed.
    *   *Handling:* Treated as '0' for specific sums but tracked as 'Unknown' in logic.

---

## Test Strategy & Coverage

We prioritize **correctness under concurrency**.

*   **Unit Tests:** Validate deduplication logic and validation rules in isolation.
*   **Integration Tests (`SpringBootTest`):** Spin up the full H2/MySQL context to test the Controller-Service-DB flow.
*   **Concurrency Tests (`ConcurrencyTest.java`):**
    *   **Race Condition Test:** Spawns 10 threads trying to insert the *same* new event ID. Asserts that only 1 succeeds and DB is consistent.
    *   **Optimistic Lock Test:** Spawns 10 threads trying to *update* the same event. Asserts that data integrity is maintained using the versioning mechanism.

---

## Benchmark Results

Benchmarks were run on the local development environment (`BenchmarkTest.java`).

*   **Test:** Ingest a batch of 1000 unique events.
*   **Target:** < 1000ms.
*   **Actual Result:** **~450ms - 800ms** (Depending on cold start).
*   **Throughput:** ~1200 - 2000 events/second.

*Note: Initial runs may be slower due to JVM warmup and connection pool initialization.*

---

## Design Tradeoffs & Limitations

*   **Tradeoff:** **Strong Consistency vs Latency.** We chose Strong Consistency (ACID) which adds latency (DB round trips). For a system processing millions of events/sec, we might switch to Eventual Consistency (Kafka + Cassandra).
*   **Limitation:** **Sync Processing.** The API blocks until all events are processed. If the batch is huge (10k), the HTTP request might timeout.
*   **Weakness:** **Database Load.** Ingesting deeply directly to main DB couples ingestion rate to DB write speed.

---

## Future Improvements

1.  **Async Ingestion:** Introduce **Apache Kafka**. API accepts event -> Pushes to Kafka -> Returns 202 Accepted immediately. Consumers process offline.
2.  **Caching:** Cache `GET /stats` results in Redis (TTL 1 min) to protect the DB from dashboard spam.
3.  **TimescaleDB:** Migrate to a time-series specific DB for better compression and time-bucket queries.

---

## Putting It All Together

This system is a robust, thread-safe backend capable of handling high-speed industrial data. It solves the hard problems of **concurrency** and **duplicate handling** using standard, proven enterprise patterns (Optimistic Locking, ACID Transactions). It is architected to be readable, testable, and ready for production-grade extensions.
#   F a c t o r y _ M a c h i n e _ E v e n t _ B a c k e n d _ S y s t e m  
 