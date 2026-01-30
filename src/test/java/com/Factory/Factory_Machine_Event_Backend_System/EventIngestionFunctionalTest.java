package com.Factory.Factory_Machine_Event_Backend_System;

import com.Factory.Factory_Machine_Event_Backend_System.model.BatchIngestResponse;
import com.Factory.Factory_Machine_Event_Backend_System.model.MachineEvent;
import com.Factory.Factory_Machine_Event_Backend_System.repository.MachineEventRepository;
import com.Factory.Factory_Machine_Event_Backend_System.service.EventIngestionService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@SpringBootTest
@Transactional
public class EventIngestionFunctionalTest {

        @Autowired
        private EventIngestionService ingestionService;

        @Autowired
        private MachineEventRepository repository;

        @BeforeEach
        public void setup() {
                repository.deleteAll();
        }

        // 1. Identical duplicate eventId -> deduped
        @Test
        public void testIdenticalDuplicateDeduplication() {
                String eventId = "DUP-001";
                MachineEvent event1 = MachineEvent.builder()
                                .eventId(eventId).machineId("M1").factoryId("F1")
                                .eventTime(Instant.now()).receivedTime(Instant.now())
                                .durationMs(100).defectCount(5).build();

                // Use same timestamp/payload for exact duplicate
                MachineEvent event2 = MachineEvent.builder()
                                .eventId(eventId).machineId("M1").factoryId("F1")
                                .eventTime(event1.getEventTime()).receivedTime(event1.getReceivedTime())
                                .durationMs(100).defectCount(5).build();

                ingestionService.processBatch(Collections.singletonList(event1));
                BatchIngestResponse response = ingestionService.processBatch(Collections.singletonList(event2));

                Assertions.assertEquals(1, response.getDeduped());
                Assertions.assertEquals(0, response.getUpdated());
                Assertions.assertEquals(0, response.getRejected());
        }

        // 2. Different payload + newer receivedTime -> update happens
        @Test
        public void testNewerUpdateWins() {
                String eventId = "UPDATE-001";
                Instant now = Instant.now();

                MachineEvent oldEvent = MachineEvent.builder()
                                .eventId(eventId).machineId("M1").factoryId("F1")
                                .eventTime(now).receivedTime(now.minusSeconds(10))
                                .durationMs(100).defectCount(1).build();

                ingestionService.processBatch(Collections.singletonList(oldEvent));

                MachineEvent newEvent = MachineEvent.builder()
                                .eventId(eventId).machineId("M1").factoryId("F1")
                                .eventTime(now).receivedTime(now) // Newer
                                .durationMs(200).defectCount(5) // Different payload
                                .build();

                BatchIngestResponse response = ingestionService.processBatch(Collections.singletonList(newEvent));

                Assertions.assertEquals(1, response.getUpdated());

                MachineEvent stored = repository.findById(eventId).orElseThrow();
                Assertions.assertEquals(5, stored.getDefectCount());
        }

        // 3. Different payload + older receivedTime -> ignored
        @Test
        public void testOlderUpdateIgnored() {
                String eventId = "IGNORE-001";
                Instant now = Instant.now();

                MachineEvent newerEvent = MachineEvent.builder()
                                .eventId(eventId).machineId("M1").factoryId("F1")
                                .eventTime(now).receivedTime(now)
                                .durationMs(100).defectCount(5).build();

                ingestionService.processBatch(Collections.singletonList(newerEvent));

                MachineEvent olderEvent = MachineEvent.builder()
                                .eventId(eventId).machineId("M1").factoryId("F1")
                                .eventTime(now).receivedTime(now.minusSeconds(20)) // Older
                                .durationMs(200).defectCount(99) // Different
                                .build();

                BatchIngestResponse response = ingestionService.processBatch(Collections.singletonList(olderEvent));

                Assertions.assertEquals(1, response.getDeduped()); // Treated as duplicate/ignored

                MachineEvent stored = repository.findById(eventId).orElseThrow();
                Assertions.assertEquals(5, stored.getDefectCount()); // Still 5
        }

        // 4. Invalid duration rejected
        @Test
        public void testInvalidDurationRejected() {
                MachineEvent event = MachineEvent.builder()
                                .eventId("BAD-DUR").machineId("M1").factoryId("F1")
                                .eventTime(Instant.now()).receivedTime(Instant.now())
                                .durationMs(-1).defectCount(0).build();

                BatchIngestResponse response = ingestionService.processBatch(Collections.singletonList(event));

                Assertions.assertEquals(1, response.getRejected());
                Assertions.assertEquals("INVALID_DURATION", response.getRejections().get(0).getReason());
        }

        // 5. Future eventTime rejected
        @Test
        public void testFutureEventRejected() {
                MachineEvent event = MachineEvent.builder()
                                .eventId("FUTURE").machineId("M1").factoryId("F1")
                                .eventTime(Instant.now().plus(Duration.ofHours(1))) // > 15 min buffer
                                .receivedTime(Instant.now())
                                .durationMs(100).defectCount(0).build();

                BatchIngestResponse response = ingestionService.processBatch(Collections.singletonList(event));

                Assertions.assertEquals(1, response.getRejected());
                Assertions.assertEquals("EVENT_IN_FUTURE", response.getRejections().get(0).getReason());
        }

        // 6. DefectCount = -1 ignored in defect totals (This requires StatsService
        // test, mostly)
        // But we can check if it's accepted
        @Test
        public void testUnknownDefectCountAccepted() {
                MachineEvent event = MachineEvent.builder()
                                .eventId("UNKNOWN-DEFECT").machineId("M1").factoryId("F1")
                                .eventTime(Instant.now()).receivedTime(Instant.now())
                                .durationMs(100).defectCount(-1).build();

                BatchIngestResponse response = ingestionService.processBatch(Collections.singletonList(event));
                Assertions.assertEquals(1, response.getAccepted());
        }

        // Helper methods for cleaner test syntax
        private void ingest(MachineEvent event) {
                ingestionService.processBatch(Collections.singletonList(event));
        }

        private MachineEvent event(String eventId, int defectCount) {
                return MachineEvent.builder()
                                .eventId(eventId)
                                .machineId("M1")
                                .factoryId("F1")
                                .eventTime(Instant.now())
                                .receivedTime(Instant.now())
                                .durationMs(100)
                                .defectCount(defectCount)
                                .build();
        }

        private MachineEvent eventAt(String isoTime) {
                Instant time = Instant.parse(isoTime);
                return MachineEvent.builder()
                                .eventId("EVT-" + isoTime) // Unique ID based on time
                                .machineId("M1")
                                .factoryId("F1")
                                .eventTime(time)
                                .receivedTime(Instant.now())
                                .durationMs(100)
                                .defectCount(0)
                                .build();
        }

        @Test
        public void defectCountMinusOneIsIgnoredInTotals() {
                ingest(event("e1", -1));
                ingest(event("e2", 4));

                long total = repository.sumKnownDefects();

                Assertions.assertEquals(4, total);
        }

        @Test
        public void startInclusiveEndExclusiveWindow() {
                ingest(eventAt("2024-01-01T10:00:00Z"));
                ingest(eventAt("2024-01-01T11:00:00Z"));

                List<MachineEvent> events = repository.findByMachineIdAndTimeRange(
                                "M1",
                                Instant.parse("2024-01-01T10:00:00Z"),
                                Instant.parse("2024-01-01T11:00:00Z"));

                Assertions.assertEquals(1, events.size());
        }

        @Test
        public void testBatchRequestedLevelDeduplication() {
                String eventId = "BATCH-DEDUP-001";
                Instant now = Instant.now();

                // 1. Oldest (should be deduped)
                MachineEvent e1 = MachineEvent.builder()
                                .eventId(eventId).machineId("M1").factoryId("F1")
                                .eventTime(now).receivedTime(now.minusSeconds(10))
                                .durationMs(100).defectCount(1).build();

                // 2. Newest (should be accepted)
                MachineEvent e2 = MachineEvent.builder()
                                .eventId(eventId).machineId("M1").factoryId("F1")
                                .eventTime(now).receivedTime(now)
                                .durationMs(200).defectCount(5).build();

                // 3. Middle (should be deduped)
                MachineEvent e3 = MachineEvent.builder()
                                .eventId(eventId).machineId("M1").factoryId("F1")
                                .eventTime(now).receivedTime(now.minusSeconds(5))
                                .durationMs(150).defectCount(3).build();

                // Mix them up in the batch
                ingestionService.processBatch(List.of(e1, e2, e3));

                // Verify DB has the "winner" (e2)
                MachineEvent stored = repository.findById(eventId).orElseThrow();
                Assertions.assertEquals(5, stored.getDefectCount());
                Assertions.assertEquals(200, stored.getDurationMs());
        }
}
