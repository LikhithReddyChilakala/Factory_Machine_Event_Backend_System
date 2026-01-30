# Factory Machine Event Backend System

This project demonstrates how a real-world backend system ingests machine events, handles duplicates and concurrency safely, and exposes meaningful analytics using clean, explainable design.

This README walks through the system end-to-end, explaining what the system does, how it is designed, and why each major architectural and implementation decision was made.

---

## Project Overview (What & Why)

### What this system does

This is a high-performance backend system designed to ingest, process, and analyze **machine events** from a factory floor. Machines send event data (timestamps, duration, defect counts) in batches. The system processes these events to ensure data integrity and provides APIs to query statistics like **top failing lines** or **machine health**.

### The Real-World Problem

In a factory, thousands of machines emit telemetry data. This data is often:

- **Noisy:** Machines might send the same event multiple times (retries)
- **Out of Order:** Network lag can cause events to arrive late
- **Concurrent:** Thousands of machines send data simultaneously
- **Corrected:** A machine might send an update to a previous event (e.g., corrected defect counts)

### Why this is NOT a simple CRUD app

A simple Create–Read–Update–Delete application would fail here because:

1. **Concurrency:** Multiple threads may process the same machine’s data simultaneously, causing race conditions.
2. **Deduplication:** Events must be deduplicated or updated using business rules, not just IDs.
3. **Performance:** The system must process **1000 events in under 1 second**, which naïve row-by-row inserts cannot handle.

### Technologies Used

- **Java 17 & Spring Boot 3** – Stable, enterprise-grade backend stack
- **MySQL** – Strong ACID guarantees for correctness under concurrency
- **Spring Data JPA (Hibernate)** – ORM with batching and indexing support
- **Lombok** – Reduces boilerplate (getters, setters, builders)

---

## System Architecture (Big Picture)

The system follows a **Layered Architecture** to ensure separation of concerns and maintainability.

### High-Level Flow

```text
[Machines]
    ↓  (HTTP POST Batch)
[Controller Layer]
    ↓
[Service Layer]
    ↓
[Repository Layer]
    ↓
[MySQL Database]
```

## Explanation of Layers

### 1. Controller Layer (`EventController`, `StatsController`)

- **Role:** Entry point of the system.
- Handles HTTP requests and JSON deserialization.
- Performs basic request validation (format-level).
- Delegates all processing to the service layer.
- Contains **no business logic**.

**Why this layer exists:**  
Keeping controllers thin ensures that API-related concerns are isolated. If the transport layer changes in the future (REST → gRPC), the business logic remains untouched.

---

### 2. Service Layer (`EventIngestionService`, `StatsService`)

- **Role:** Core business logic of the system.
- Responsible for:
  - Validation rules
  - Deduplication logic
  - Update logic
  - Concurrency handling
  - Statistics computation
- Uses `@Transactional` to ensure atomicity for batch operations.

**Why this layer exists:**  
All domain rules live in one place, making the system easier to reason about, test, and defend in interviews.

---

### 3. Repository Layer (`MachineEventRepository`)

- **Role:** Database access abstraction.
- Encapsulates CRUD operations and custom queries.
- Handles aggregation queries for statistics.

**Why this layer exists:**  
Separating persistence logic keeps SQL and database concerns isolated from business rules.

---

## API Layer (Controllers Deep Dive)

### 1. Ingestion API

- **Endpoint:** `POST /events/batch`
- **Purpose:** Accepts batches of machine events.

**Design Philosophy:**
- **Batching:** Machines send multiple events at once to reduce network overhead.
- **Partial Success Model:** Each event returns its own status (`ACCEPTED`, `DEDUPED`, `UPDATED`, `REJECTED`) so one invalid event does not fail the entire batch.

---

### 2. Query / Stats APIs

- **Endpoint:** `GET /stats?machineId=&start=&end=`
  - Returns machine-level health and defect statistics for a given time window.

- **Endpoint:** `GET /stats/top-defect-lines`
  - Identifies production lines with the highest defect counts.

---

## Database Design (Deep Dive)

A **Relational Database (MySQL)** is used because the data is structured and requires strong consistency guarantees.

### Entity: `MachineEvent` (Table: `machine_events`)

| Column         | Type           | Purpose |
|---------------|----------------|---------|
| `eventId`      | VARCHAR (PK)   | Unique identifier for each event |
| `machineId`    | VARCHAR        | Identifies the source machine |
| `eventTime`    | TIMESTAMP      | When the event occurred (machine time) |
| `receivedTime` | TIMESTAMP      | When the backend received the event |
| `durationMs`   | BIGINT         | Duration of the machine cycle |
| `defectCount`  | INT            | Defects produced (`-1` = unknown) |
| `version`      | BIGINT         | Optimistic locking field |

---

### Indexing Strategy

- **Primary Key (`eventId`)**
  - Enables fast deduplication and idempotent writes.

- **Composite Index (`machineId`, `eventTime`)**
  - Used by machine-level stats queries.
  - Filters by machine first, then performs a range scan on time.

- **Single Index (`eventTime`)**
  - Used for global time-window analytics such as top defect lines.

---

## Deduplication & Update Logic (Core Logic)

The system uses **Latest Writer Wins** combined with **Payload Comparison**.

### Algorithm (`EventIngestionService.attemptUpsert`)

1. Query the database using `eventId`.
2. **If event does not exist**
   - Insert record
   - Status: `ACCEPTED`
3. **If event exists**
   - If incoming `receivedTime` is older → ignore → `DEDUPED`
   - If payload is identical → ignore → `DEDUPED`
   - If newer and payload differs → update record → `UPDATED`

**Why this is safe:**  
Out-of-order delivery is handled correctly. Stale updates never overwrite newer data.

---

## Thread-Safety Strategy

### The Problem

Concurrent updates to the same `eventId` can overwrite each other, causing data loss (lost update problem).

---

### The Solution: Optimistic Locking + Retry

- A `@Version` field is used on the entity.
- Hibernate includes the version in `UPDATE` statements.
- If another thread updates the same row first:
  - The update fails
  - An `OptimisticLockingFailureException` is thrown
- The service retries the operation after reloading the latest data.

---

### Why Not Other Approaches?

- **`synchronized`:** Works only on a single JVM; fails in distributed deployments.
- **Pessimistic locking:** Blocks rows and severely limits throughput.
- **Optimistic locking:** Non-blocking, scalable, and ideal for this workload.

---

## Performance Optimizations

To meet the **1000 events < 1 second** requirement:

1. **Batch Ingestion:** Processes lists of events in a single transaction.
2. **Conditional Updates:** Updates occur only when payload changes.
3. **Indexing:** Enables millisecond-level query performance.
4. **Connection Pooling:** HikariCP efficiently manages database connections.

---

## Validation Rules & Special Cases

- **Future Events:** `eventTime > now + 15 minutes` → rejected.
- **Invalid Duration:** `duration < 0` or `duration > 6 hours` → rejected.
- **Defect Count = -1:** Stored but ignored in defect calculations.

---

## Test Strategy & Coverage

Focus: **Correctness under concurrency**

- **Unit Tests:** Validate deduplication and validation logic.
- **Integration Tests:** Validate Controller → Service → DB flow.
- **Concurrency Tests:**
  - Multiple threads inserting the same event.
  - Concurrent updates using optimistic locking.

---

## Benchmark Results

- **Batch Size:** 1000 events
- **Target:** < 1000 ms
- **Observed:** ~450–800 ms
- **Throughput:** ~1200–2000 events/sec

> Initial runs may be slower due to JVM warm-up and connection pool initialization.

---

## Design Tradeoffs & Limitations

- Strong consistency preferred over low latency.
- Synchronous ingestion may block for very large batches.
- Database write throughput is the primary bottleneck.

---

## Future Improvements

1. Kafka-based asynchronous ingestion
2. Redis caching for stats APIs
3. Time-series databases such as TimescaleDB

---

## Putting It All Together

This system is a production-style, thread-safe backend designed to handle noisy, concurrent industrial data using proven enterprise patterns such as **Optimistic Locking** and **ACID transactions**.

