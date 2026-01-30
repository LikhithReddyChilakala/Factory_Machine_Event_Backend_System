package com.antigravity.Factory_Machine_Event_Backend_System.service;

import com.antigravity.Factory_Machine_Event_Backend_System.model.MachineEvent;
import com.antigravity.Factory_Machine_Event_Backend_System.repository.MachineEventRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class StatsService {

    private final MachineEventRepository repository;

    public StatsService(MachineEventRepository repository) {
        this.repository = repository;
    }

    public MachineStats getMachineStats(String machineId, Instant start, Instant end) {
        List<MachineEvent> events = repository.findByMachineIdAndtimeRange(machineId, start, end);

        long totalEvents = events.size();
        long totalDefects = events.stream()
                .filter(e -> e.getDefectCount() != -1)
                .mapToInt(MachineEvent::getDefectCount)
                .sum();

        double hours = Duration.between(start, end).toMillis() / 3600000.0;
        if (hours == 0)
            hours = 1.0; // Avoid divide by zero for tiny windows

        double avgDefects = (totalEvents == 0) ? 0.0 : (double) totalDefects / hours;
        BigDecimal roundedRate = BigDecimal.valueOf(avgDefects).setScale(1, RoundingMode.HALF_UP);

        String status = avgDefects < 2.0 ? "Healthy" : "Warning";

        return new MachineStats(machineId, start, end, totalEvents, totalDefects, roundedRate.doubleValue(), status);
    }

    public List<TopDefectLine> getTopDefectLines(Instant start, Instant end, int limit) {
        // Query returns Object[]: {machineId, totalDefects, eventCount}
        List<Object[]> results = repository.findTopDefectLines(start, end);

        List<TopDefectLine> lines = new ArrayList<>();
        for (int i = 0; i < Math.min(results.size(), limit); i++) {
            Object[] row = results.get(i);
            String lineId = (String) row[0];
            long totalDefects = ((Number) row[1]).longValue();
            long eventCount = ((Number) row[2]).longValue();

            double defectPercent = eventCount == 0 ? 0 : (double) totalDefects * 100.0 / eventCount;
            BigDecimal roundedPercent = BigDecimal.valueOf(defectPercent).setScale(2, RoundingMode.HALF_UP);

            lines.add(new TopDefectLine(lineId, totalDefects, eventCount, roundedPercent.doubleValue()));
        }
        return lines;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class MachineStats {
        private String machineId;
        private Instant start;
        private Instant end;
        private long eventsCount;
        private long defectsCount;
        private double avgDefectRate;
        private String status;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class TopDefectLine {
        private String lineId;
        private long totalDefects;
        private long eventCount;
        private double defectsPercent;
    }

}
