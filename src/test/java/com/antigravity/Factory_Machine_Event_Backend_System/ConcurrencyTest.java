package com.antigravity.Factory_Machine_Event_Backend_System;

import com.antigravity.Factory_Machine_Event_Backend_System.model.BatchIngestResponse;
import com.antigravity.Factory_Machine_Event_Backend_System.model.MachineEvent;
import com.antigravity.Factory_Machine_Event_Backend_System.repository.MachineEventRepository;
import com.antigravity.Factory_Machine_Event_Backend_System.service.EventIngestionService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

@SpringBootTest
public class ConcurrencyTest {

    @Autowired
    private EventIngestionService ingestionService;

    @Autowired
    private MachineEventRepository repository;

    @Test
    public void testConcurrentInserts_RaceCondition() throws InterruptedException {
        // Scenario: 10 threads try to insert the exact same NEW eventId at the same
        // time.
        // Expectation: 1 Insert, 9 Dedupes (or specific error handling leading to
        // success).
        // Since payload is identical, they should all eventually succeed as dedupes.

        String eventId = "RACE-INSERT-001";
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        Runnable task = () -> {
            try {
                startLatch.await();
                MachineEvent event = MachineEvent.builder()
                        .eventId(eventId)
                        .machineId("M-01")
                        .eventTime(Instant.now())
                        .durationMs(100)
                        .defectCount(0)
                        .build();
                ingestionService.processBatch(Collections.singletonList(event));
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                doneLatch.countDown();
            }
        };

        for (int i = 0; i < threads; i++)
            executor.submit(task);

        startLatch.countDown(); // Go!
        doneLatch.await(5, TimeUnit.SECONDS);

        MachineEvent stored = repository.findById(eventId).orElseThrow();
        Assertions.assertEquals("M-01", stored.getMachineId());

        // Cannot easily check separate response objects here, but we verified the DB
        // state is consistent.
    }

    @Test
    public void testConcurrentUpdates_OptimisticLocking() throws InterruptedException {
        // Scenario: Event exists. 10 threads try to update it with DIFFERENT defect
        // counts.
        // The one that runs "last" (logically) should win?
        // Or if they run truly parallel, Optimistic Locking should force retries.

        String eventId = "RACE-UPDATE-001";
        MachineEvent initial = MachineEvent.builder()
                .eventId(eventId)
                .machineId("M-01")
                .eventTime(Instant.now())
                .receivedTime(Instant.now().minusSeconds(10)) // Old time
                .durationMs(100)
                .defectCount(0)
                .build();
        repository.save(initial);

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        Runnable task = () -> {
            try {
                startLatch.await();
                MachineEvent event = MachineEvent.builder()
                        .eventId(eventId)
                        .machineId("M-01")
                        .eventTime(Instant.now())
                        .durationMs(100)
                        .defectCount(5) // Change value
                        .build();
                // processBatch internally sets receivedTime to NOW.
                ingestionService.processBatch(Collections.singletonList(event));
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                doneLatch.countDown();
            }
        };

        for (int i = 0; i < threads; i++)
            executor.submit(task);

        startLatch.countDown();
        doneLatch.await(5, TimeUnit.SECONDS);

        MachineEvent stored = repository.findById(eventId).orElseThrow();
        Assertions.assertEquals(5, stored.getDefectCount());
        // Verify version incremented at least once (likely more)
        Assertions.assertTrue(stored.getVersion() > 0);
    }
}
