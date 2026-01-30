package com.Factory.Factory_Machine_Event_Backend_System.controller;

import com.Factory.Factory_Machine_Event_Backend_System.service.StatsService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping
    public ResponseEntity<StatsService.MachineStats> getStats(
            @RequestParam String machineId,
            @RequestParam Instant start,
            @RequestParam Instant end) {
        return ResponseEntity.ok(statsService.getMachineStats(machineId, start, end));
    }

    @GetMapping("/top-defect-lines")
    public ResponseEntity<List<StatsService.TopDefectLine>> getTopDefectLines(
            @RequestParam(required = false) String factoryId, // Not used in calculation per prompt, but in signature
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(statsService.getTopDefectLines(from, to, limit));
    }
}
