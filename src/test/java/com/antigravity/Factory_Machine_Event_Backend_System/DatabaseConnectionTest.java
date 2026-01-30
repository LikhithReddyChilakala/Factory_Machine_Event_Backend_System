package com.antigravity.Factory_Machine_Event_Backend_System;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.Factory.machineevents.model.MachineEvent;
import com.Factory.machineevents.repository.MachineEventRepository;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/factory",
        "spring.datasource.username=root",
        "spring.datasource.password=Likhith@2003",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.hibernate.ddl-auto=update"
})
class DatabaseConnectionTest {

    @Autowired
    private MachineEventRepository repository;

    @Test
    @Transactional
    @Rollback(false)
    void testDatabaseConnectionAndPersistence() {
        String testEventId = UUID.randomUUID().toString();
        MachineEvent event = MachineEvent.builder()
                .eventId(testEventId)
                .machineId("TEST-MACHINE-01")
                .eventTime(Instant.now())
                .receivedTime(Instant.now())
                .durationMs(100)
                .defectCount(0)
                .build();

        // 1. Save
        MachineEvent saved = repository.save(event);
        assertThat(saved.getEventId()).isEqualTo(testEventId);

        // 2. Retrieve
        MachineEvent retrieved = repository.findById(testEventId).orElse(null);
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getMachineId()).isEqualTo("TEST-MACHINE-01");

        System.out
                .println(">>> SUCCESS: Successfully saved and retrieved event " + testEventId + " from the database.");
    }
}
