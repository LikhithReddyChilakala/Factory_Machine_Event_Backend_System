package com.antigravity.machineevents;

import com.antigravity.machineevents.model.BatchIngestResponse;
import com.antigravity.machineevents.model.MachineEvent;
import com.antigravity.machineevents.service.EventIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@SpringBootTest
public class BenchmarkTest {

    @Autowired
    private EventIngestionService ingestionService;

    @Test
    public void benchmarkIngest1000Events() {
        int count = 1000;
        List<MachineEvent> batch = new ArrayList<>(count);
        Instant now = Instant.now();

        for (int i = 0; i < count; i++) {
            batch.add(MachineEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .machineId("M-BENCH")
                    .eventTime(now)
                    .receivedTime(now)
                    .durationMs(100)
                    .defectCount(0)
                    .build());
        }

        long start = System.currentTimeMillis();
        BatchIngestResponse response = ingestionService.processBatch(batch);
        long end = System.currentTimeMillis();

        System.out.println("BENCHMARK-RESULT: Ingested " + count + " events in " + (end - start) + "ms");

        if ((end - start) > 1000) {
            System.err.println("WARNING: Benchmark failed to meet 1000ms target!");
        }
    }
}
