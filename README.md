# Factory Machine Event Backend System

This project is a high-performance backend system designed to ingest, process, and analyze **machine events** from a factory floor. It handles extreme concurrency, filters noisy telemetry, and provides real-time analytics using enterprise-grade Java and Spring Boot patterns.

---

## Project Overview (The "Why")

In an industrial environment, machines emit constant telemetry data. This data is often:
- **Noisy:** Network retries lead to duplicate events.
- **Concurrent:** Thousands of machines send data simultaneously.
- **Out-of-Order:** Network lag causes older updates to arrive after newer ones.
- **Corrected:** Machines might send corrected metrics (e.g., defect counts) after the initial event.

### Engineering Challenges
- **Deduplication:** Needs O(n) efficiency to avoid saturation.
- **Performance:** Processes **1000 events in under 1 second**.
- **Data Integrity:** Prevents the "Lost Update" problem using **Optimistic Locking**.

---

## System Architecture

The system follows a **Layered Architecture** to ensure separation of concerns and maintainability.

### High-Level Flow
```text
[Machines] -> [Controller Layer] -> [Service Layer] -> [Repository Layer] -> [MySQL]
```

#### 1. Controller Layer (`EventController`, `StatsController`)
- **Role:** Entry point; handles HTTP requests and basic request validation.
- **Rationale:** Isolated from business logic. If the transport changes (REST -> gRPC), the core logic remains untouched.

#### 2. Service Layer (`EventIngestionService`, `StatsService`)
- **Role:** The "Brain" of the system. Implements deduplication, update logic, and statistics computation.
- **Rationale:** Centralizes all domain rules, making the system easy to test and defend in audits.

#### 3. Repository Layer (`MachineEventRepository`)
- **Role:** Database abstraction using Spring Data JPA.
- **Rationale:** Encapsulates complex SQL and aggregation queries away from business logic.

---

## The 3-Stage Ingestion Lifecycle (The logic)

To meet performance targets while maintaining 100% integrity, the ingestion process follows three distinct stages:

### Stage 1: In-Memory Request Deduplication
Before touching the database, the system optimizes the incoming batch:
- **Resolution:** If the same `eventId` appears multiple times *within one request*, only the one with the **latest `receivedTime`** is selected.
- **Validation:** Events with invalid durations (<0 or >6h) or future timestamps (>15m from now) are rejected.

### Stage 2: Fast-Path (Optimistic Batch Processing)
This is the "Happy Path" optimized for high throughput:
1.  **O(1) Pre-fetch:** Loads all existing records for the batch in a single SQL query.
2.  **State Comparison:** 
    - **New:** Inserted as `ACCEPTED`.
    - **Updates:** Updated only if incoming `receivedTime` is newer **and** payload differs.
    - **Duplicates:** If data is identical, it's marked as `DEDUPED` to skip the SQL `UPDATE`.
3.  **Atomic Save:** Flushes all valid changes in one database transaction.

### Stage 3: Resilient Fallback (Self-Healing)
If a concurrency conflict occurs (someone else updated the row between our fetch and save):
- The system automatically falls back to **row-by-row processing**.
- Each event gets its own transaction and **retry loop (up to 3 times)**.
- Using `@Version` locking, it reloads the latest state and merges the data safely.

---

## Database Design & Indexing

A relational MySQL database is used to provide strong ACID consistency.

### Entity: `MachineEvent`
| Column | Type | Purpose |
| :--- | :--- | :--- |
| `eventId` | VARCHAR(PK) | Unique event identifier. |
| `machineId` | VARCHAR | Source machine ID. |
| `factoryId` | VARCHAR | Factory/Line identifier. |
| `eventTime` | TIMESTAMP | When the machine generated the event. |
| `receivedTime`| TIMESTAMP | When the backend received the event. |
| `durationMs` | BIGINT | Cycle duration. |
| `defectCount` | INT | Defects produced (-1 for unknown). |
| `version` | BIGINT | **Optimistic Locking** version field. |

### Indexing Strategy
- **`PRIMARY KEY (eventId)`**: Enables instantaneous deduplication checks.
- **`INDEX (machineId, eventTime)`**: Optimized for machine-level range queries used in `/stats`.
- **`INDEX (eventTime)`**: Optimized for global time-window reports like Top Defect Lines.

---

## API Documentation

### 1. Ingestion API
`POST /events/batch`
- **Response:** `BatchIngestResponse` containing counts for `accepted`, `updated`, `deduped` and a list of `rejections`.

### 2. Stats APIs
- **Machine Stats:** `GET /stats?machineId={id}&start={iso}&end={iso}`
- **Top Defect Lines:** `GET /stats/top-defect-lines?from={iso}&to={iso}&limit={n}`
  - *Note: Uses `from`/`to` parameters for precision.*

---

## Thread-Safety Strategy

The system uses **Optimistic Locking** instead of `synchronized` or Pessimistic locks.
- **Why?** It doesn't block database reads or other writes, allowing massive parallel throughput.
- **How?** Hibernate checks the `version` column. If a collision is detected, our **Stage 3 Fallback** handles the recovery.

---

## Test & Performance

### Test Strategy
- **Functional Tests:** Validate the 3-stage ingestion logic.
- **Concurrency Tests:** Stress test with multiple threads updating the same `eventId` simultaneously to verify `@Version` retries.
- **Integration Tests:** Verify the full flow from Controller to MySQL.

### Performance Benchmarks
- **Batch Size:** 1000 events
- **Target:** < 1000 ms
- **Observed:** **~450ms - 800ms**
- **Throughput:** ~1200 - 2200 events/sec

---

## Tech Stack Highlights
- **Java 17 & Spring Boot 3**
- **Lombok:** Eliminates boilerplate and uses `@Builder` for clean test data.
- **Hibernate @DynamicUpdate:** Only generates SQL `UPDATE` for changed columns.
- **HikariCP:** Efficiently manages the database connection pool.

---

## Future Roadmap
1. Kafka integration for asynchronous high-volume ingestion.
2. Redis for caching statistics results.
3. TimescaleDB for advanced time-series analysis.
