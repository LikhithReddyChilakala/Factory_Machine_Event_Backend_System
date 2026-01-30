package com.antigravity.machineevents.controller;

import com.antigravity.machineevents.model.BatchIngestResponse;
import com.antigravity.machineevents.model.MachineEvent;
import com.antigravity.machineevents.service.EventIngestionService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/events")
public class EventController {

    private final EventIngestionService ingestionService;

    public EventController(EventIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/batch")
    public ResponseEntity<BatchIngestResponse> ingestBatch(@RequestBody List<MachineEvent> events) {
        BatchIngestResponse response = ingestionService.processBatch(events);
        return ResponseEntity.ok(response);
    }
}
