package com.Factory.Factory_Machine_Event_Backend_System.service;
import com.Factory.Factory_Machine_Event_Backend_System.model.BatchIngestResponse;
import com.Factory.Factory_Machine_Event_Backend_System.model.MachineEvent;
import com.Factory.Factory_Machine_Event_Backend_System.repository.MachineEventRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
@Service
public class EventIngestionService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EventIngestionService.class);
    private final MachineEventRepository repository;
    private final EventIngestionService self; // Self-reference for AOP proxy
    public EventIngestionService(MachineEventRepository repository, @Lazy EventIngestionService self) {
        this.repository = repository;
        this.self = self;
    }
    private static final int MAX_RETRIES = 3;
    /**
     * Process a batch of events using Optimistic Batching with Fallback.
     */
    public BatchIngestResponse processBatch(List<MachineEvent> events) {
        BatchIngestResponse response = new BatchIngestResponse();
        Instant now = Instant.now();
        List<MachineEvent> validEvents = new ArrayList<>();
        // 1. VALIDATION PHASE
        // Separate validation so we don't re-validate in fallback
        for (MachineEvent event : events) {
            String rejectionReason = validateEvent(event, now);
            if (rejectionReason != null) {
                response.addRejection(event.getEventId(), rejectionReason);
                continue;
            }
            if (event.getReceivedTime() == null) {
                event.setReceivedTime(now);
            }
            validEvents.add(event);
        }
        if (validEvents.isEmpty()) {
            return response;
        }
        // 2. FAST PATH: OPTIMISTIC BATCH PROCESSING
        try {
            processBatchOptimistic(validEvents, response);
        } catch (Exception e) {
            // 3. SLOW PATH: FALLBACK
            // If ANY event caused a concurrency error, the whole batch rolled back.
            // Switch to processing one-by-one to isolate and handle the conflict.
            log.warn("Optimistic batch save failed. Falling back to row-by-row processing. Error: {}", e.getMessage());

            // Reset counters (since the batch failed)
            // Rejections are preserved as they were statically determined
            response.setAccepted(0);
            response.setUpdated(0);
            response.setDeduped(0);
            processFallback(validEvents, response);
        }
        return response;
    }
    private void processBatchOptimistic(List<MachineEvent> validEvents, BatchIngestResponse response) {
        // A. Pre-fetch all existing records in ONE query (O(1))
        List<String> eventIds = validEvents.stream()
                .map(MachineEvent::getEventId)
                .collect(Collectors.toList());
        Map<String, MachineEvent> existingMap = repository.findAllById(eventIds).stream()
                .collect(Collectors.toMap(MachineEvent::getEventId, e -> e));
        List<MachineEvent> batchToSave = new ArrayList<>();

        // Temp counters
        int tempAccepted = 0;
        int tempUpdated = 0;
        int tempDeduped = 0;
        // B. In-Memory Logic
        for (MachineEvent incoming : validEvents) {
            MachineEvent existing = existingMap.get(incoming.getEventId());
            if (existing == null) {
                // New Event
                batchToSave.add(incoming);
                tempAccepted++;
            } else {
                // Existing Event
                if (incoming.getReceivedTime().isAfter(existing.getReceivedTime())) {
                    if (incoming.hasSamePayload(existing)) {
                        tempDeduped++;
                    } else {
                        // Update relevant fields
                        existing.setDurationMs(incoming.getDurationMs());
                        existing.setDefectCount(incoming.getDefectCount());
                        existing.setEventTime(incoming.getEventTime());
                        existing.setReceivedTime(incoming.getReceivedTime());

                        batchToSave.add(existing);
                        tempUpdated++;
                    }
                } else {
                    tempDeduped++;
                }
            }
        }
        // C. Bulk Write (O(1))
        if (!batchToSave.isEmpty()) {
            self.saveBulkInternal(batchToSave);
        }
        // Only update response if successful
        response.setAccepted(response.getAccepted() + tempAccepted);
        response.setUpdated(response.getUpdated() + tempUpdated);
        response.setDeduped(response.getDeduped() + tempDeduped);
    }
    @Transactional
    public void saveBulkInternal(List<MachineEvent> batch) {
        repository.saveAll(batch);
        repository.flush(); // Force immediate execution to catch constraints/locking
    }
    private void processFallback(List<MachineEvent> validEvents, BatchIngestResponse response) {
        for (MachineEvent event : validEvents) {
            try {
                processSingleEvent(event, response);
            } catch (Exception e) {
                log.error("Unexpected error processing event {} in fallback", event.getEventId(), e);
                response.addRejection(event.getEventId(), "INTERNAL_ERROR");
            }
        }
    }
    private String validateEvent(MachineEvent event, Instant now) {
        if (event.getEventId() == null || event.getEventId().trim().isEmpty()) {
            return "MISSING_EVENT_ID";
        }
        if (event.getDurationMs() < 0 || event.getDurationMs() > Duration.ofHours(6).toMillis()) {
            return "INVALID_DURATION";
        }
        if (event.getEventTime().isAfter(now.plus(Duration.ofMinutes(15)))) {
            return "EVENT_IN_FUTURE";
        }
        return null;
    }
    private void processSingleEvent(MachineEvent incoming, BatchIngestResponse response) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                self.attemptUpsert(incoming, response);
                return;
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) {
                    response.addRejection(
                            incoming.getEventId(),
                            "CONCURRENCY_FAILURE");
                }
            }
        }
    }
    // Public and @Transactional for AOP
    @Transactional
    public void attemptUpsert(MachineEvent incoming, BatchIngestResponse response) {
        if (incoming.getEventId() == null) {
            throw new IllegalArgumentException("Event ID cannot be null");
        }

        Optional<MachineEvent> existingOpt = repository.findById(incoming.getEventId());
        if (existingOpt.isEmpty()) {
            try {
                repository.saveAndFlush(incoming);
                response.setAccepted(response.getAccepted() + 1);
            } catch (DataIntegrityViolationException e) {
                throw e;
            }
        } else {
            MachineEvent existing = existingOpt.get();
            if (incoming.getReceivedTime().isAfter(existing.getReceivedTime())) {
                if (incoming.hasSamePayload(existing)) {
                    response.setDeduped(response.getDeduped() + 1);
                } else {
                    existing.setDurationMs(incoming.getDurationMs());
                    existing.setDefectCount(incoming.getDefectCount());
                    existing.setEventTime(incoming.getEventTime());
                    existing.setMachineId(incoming.getMachineId());
                    existing.setReceivedTime(incoming.getReceivedTime());
                    repository.saveAndFlush(existing);
                    response.setUpdated(response.getUpdated() + 1);
                }
            } else {
                response.setDeduped(response.getDeduped() + 1);
            }
        }
    }
}