package com.Factory.Factory_Machine_Event_Backend_System.service;

import com.Factory.Factory_Machine_Event_Backend_System.model.BatchIngestResponse;
import com.Factory.Factory_Machine_Event_Backend_System.model.MachineEvent;
import com.Factory.Factory_Machine_Event_Backend_System.repository.MachineEventRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class EventIngestionService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EventIngestionService.class);
    private final MachineEventRepository repository;
    private final EventIngestionService self;

    public EventIngestionService(MachineEventRepository repository, @Lazy EventIngestionService self) {
        this.repository = repository;
        this.self = self;
    }

    private static final int MAX_RETRIES = 3;

    public BatchIngestResponse processBatch(List<MachineEvent> events) {
        BatchIngestResponse response = new BatchIngestResponse();
        Instant now = Instant.now();
        List<MachineEvent> validEvents = new ArrayList<>();

        // 1. Validation & Defaulting
        for (MachineEvent event : events) {
            String error = validateEvent(event, now);
            if (error != null) {
                response.addRejection(event.getEventId(), error);
                continue;
            }
            if (event.getReceivedTime() == null)
                event.setReceivedTime(now);
            validEvents.add(event);
        }
        if (validEvents.isEmpty())
            return response;

        // 2. Request-Level Deduplication (Latest receivedTime wins)
        Map<String, MachineEvent> uniqueMap = new HashMap<>();
        for (MachineEvent e : validEvents) {
            MachineEvent existing = uniqueMap.get(e.getEventId());
            if (existing == null || e.getReceivedTime().isAfter(existing.getReceivedTime())) {
                if (existing != null)
                    response.setDeduped(response.getDeduped() + 1);
                uniqueMap.put(e.getEventId(), e);
            } else {
                response.setDeduped(response.getDeduped() + 1);
            }
        }
        List<MachineEvent> dedupedEvents = new ArrayList<>(uniqueMap.values());

        // 3. Fast Path: Optimistic Batch Processing
        try {
            processBatchOptimistic(dedupedEvents, response);
        } catch (Exception e) {
            log.warn("Optimistic batch failed, falling back to row-by-row: {}", e.getMessage());
            response.setAccepted(0);
            response.setUpdated(0);
            response.setDeduped(0); // Reset stats for fallback re-calculation
            processFallback(dedupedEvents, response);
        }
        return response;
    }

    private void processBatchOptimistic(List<MachineEvent> batch, BatchIngestResponse response) {
        // Pre-fetch existing records in O(1) query
        List<String> ids = batch.stream()
                .map(MachineEvent::getEventId)
                .collect(Collectors.toList());

        // Pre-fetch existing records in O(1) query
        Map<String, MachineEvent> dbMap = repository.findAllById(ids)
                .stream().collect(Collectors.toMap(MachineEvent::getEventId, e -> e));

        List<MachineEvent> toSave = new ArrayList<>();
        int acc = 0, upd = 0, ded = 0;

        for (MachineEvent in : batch) {
            MachineEvent ex = dbMap.get(in.getEventId());
            if (ex == null) {
                toSave.add(in);
                acc++; // New Event
            } else if (in.getReceivedTime().isAfter(ex.getReceivedTime())) {
                if (in.hasSamePayload(ex)) {
                    ded++; // Duplicate payload
                } else {
                    // Update field-by-field to keep managed entity state
                    ex.setDurationMs(in.getDurationMs());
                    ex.setDefectCount(in.getDefectCount());
                    ex.setEventTime(in.getEventTime());
                    ex.setReceivedTime(in.getReceivedTime());
                    ex.setMachineId(in.getMachineId());
                    ex.setFactoryId(in.getFactoryId());
                    toSave.add(ex);
                    upd++;
                }
            } else {
                ded++; // Older update ignored
            }
        }

        if (!toSave.isEmpty())
            self.saveBulkInternal(toSave);
        response.setAccepted(response.getAccepted() + acc);
        response.setUpdated(response.getUpdated() + upd);
        response.setDeduped(response.getDeduped() + ded);
    }

    @Transactional
    public void saveBulkInternal(List<MachineEvent> batch) {
        repository.saveAll(batch);
        repository.flush();
    }

    private void processFallback(List<MachineEvent> events, BatchIngestResponse response) {
        for (MachineEvent event : events) {
            try {
                processSingleEvent(event, response);
            } catch (Exception e) {
                log.error("Error processing event {} in fallback", event.getEventId(), e);
                response.addRejection(event.getEventId(), "INTERNAL_ERROR");
            }
        }
    }

    private String validateEvent(MachineEvent e, Instant now) {
        if (e.getEventId() == null || e.getEventId().trim().isEmpty())
            return "MISSING_EVENT_ID";
        if (e.getDurationMs() < 0 || e.getDurationMs() > Duration.ofHours(6).toMillis())
            return "INVALID_DURATION";
        if (e.getEventTime().isAfter(now.plus(Duration.ofMinutes(15))))
            return "EVENT_IN_FUTURE";
        return null;
    }

    private void processSingleEvent(MachineEvent in, BatchIngestResponse res) {
        for (int i = 1; i <= MAX_RETRIES; i++) {
            try {
                self.attemptUpsert(in, res);
                return;
            } catch (Exception e) {
                if (i == MAX_RETRIES)
                    res.addRejection(in.getEventId(), "CONCURRENCY_FAILURE");
            }
        }
    }

    @Transactional
    public void attemptUpsert(MachineEvent in, BatchIngestResponse res) {
        if (in.getEventId() == null)
            throw new IllegalArgumentException("Event ID null");

        Optional<MachineEvent> opt = repository.findById(in.getEventId());
        if (opt.isEmpty()) {
            try {
                repository.saveAndFlush(in);
                res.setAccepted(res.getAccepted() + 1);
            } catch (DataIntegrityViolationException e) {
                throw e; // Retry signal
            }
        } else {
            MachineEvent ex = opt.get();
            if (in.getReceivedTime().isAfter(ex.getReceivedTime())) {
                if (in.hasSamePayload(ex)) {
                    res.setDeduped(res.getDeduped() + 1);
                } else {
                    ex.setDurationMs(in.getDurationMs());
                    ex.setDefectCount(in.getDefectCount());
                    ex.setEventTime(in.getEventTime());
                    ex.setMachineId(in.getMachineId());
                    ex.setFactoryId(in.getFactoryId());
                    ex.setReceivedTime(in.getReceivedTime());
                    repository.saveAndFlush(ex);
                    res.setUpdated(res.getUpdated() + 1);
                }
            } else {
                res.setDeduped(res.getDeduped() + 1);
            }
        }
    }
}