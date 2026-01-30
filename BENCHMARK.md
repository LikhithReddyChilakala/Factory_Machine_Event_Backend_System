# Benchmark Report

## 1. Test Environment

*   **Operating System:** Windows
*   **CPU:** 13th Gen Intel(R) Core(TM) i7-1355U
*   **RAM:** 16 GB
*   **Java Version:** 17

## 2. Methodology

*   **Command:** `mvn test -Dtest=BenchmarkTest`
*   **Test Case:** `BenchmarkTest.benchmarkIngest1000Events`
*   **Workload:**
    *   **Batch Size:** 1000 events
    *   **Concurrency:** Single batch processed via `EventIngestionService`.
    *   **Database:** MySQL (Localhost)
    *   **Validation:** Full validation enabled (Future checks, duration checks).
    *   **Deduplication:** Enabled (DB lookups + Payload comparison).

## 3. Results

| Metric | Result | Target | Status |
| :--- | :--- | :--- | :--- |
| **Ingestion Time (1000 events)** | **~450ms - 800ms** | < 1000ms | ✅ PASS |
| **Throughput** | **~1200 - 2200 events/sec** | > 1000 events/sec | ✅ PASS |

*> Note: Variance depends on JVM warmup and connection pool initialization (HikariCP).*

## 4. Observations & Optimizations

### Optimizations Implemented:
1.  **Batch Processing:** The API accepts a `List<MachineEvent>` to minimize HTTP overhead.
2.  **Optimistic Locking:** Used `@Version` instead of Pessimistic Locking to handle concurrent updates without blocking database reads.
3.  **Composite Indexes:** Added `idx_machine_time` (machineId + eventTime) to ensure stats queries remain O(log N) even with millions of rows.
4.  **Payload Hashing:** Logic checks `hasSamePayload()` before issuing an UPDATE to avoid unnecessary DB writes.

### Tradeoffs:
*   **Consistency vs Speed:** We chose ACID consistency (MySQL) over raw speed (e.g., Redis/Kafka). This ensures 0% data loss but caps max throughput at the DB's write IOPs.
*   **Validation:** Strict validation rules (e.g., checking future timestamps) add a small CPU cost per event but ensure data quality.
