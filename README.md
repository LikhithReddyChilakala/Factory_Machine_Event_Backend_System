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
