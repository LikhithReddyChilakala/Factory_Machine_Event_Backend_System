package com.Factory.Factory_Machine_Event_Backend_System.service;

import com.Factory.Factory_Machine_Event_Backend_System.model.BatchIngestResponse;
import com.Factory.Factory_Machine_Event_Backend_System.model.MachineEvent;
import com.Factory.Factory_Machine_Event_Backend_System.repository.MachineEventRepository;

import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
     * Process a batch of events.
     */
    public BatchIngestResponse processBatch(List<MachineEvent> events) {
        BatchIngestResponse response = new BatchIngestResponse();
        Instant now = Instant.now();

        for (MachineEvent event : events) {
            String rejectionReason = validateEvent(event, now);
            if (rejectionReason != null) {
                response.addRejection(event.getEventId(), rejectionReason);
                continue;
            }

            if (event.getReceivedTime() == null) {
                event.setReceivedTime(now);
            }

            try {
                processSingleEvent(event, response);
            } catch (Exception e) {
                log.error("Unexpected error processing event {}", event.getEventId(), e);
                response.addRejection(event.getEventId(), "INTERNAL_ERROR");
            }
        }
        return response;
    }

    private String validateEvent(MachineEvent event, Instant now) {
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
