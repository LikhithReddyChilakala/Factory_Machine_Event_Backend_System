package com.Factory.Factory_Machine_Event_Backend_System.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.Factory.Factory_Machine_Event_Backend_System.model.BatchIngestResponse;
import com.Factory.Factory_Machine_Event_Backend_System.model.MachineEvent;
import com.Factory.Factory_Machine_Event_Backend_System.service.EventIngestionService;

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
