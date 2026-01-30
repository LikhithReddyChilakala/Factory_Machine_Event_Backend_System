package com.Factory.Factory_Machine_Event_Backend_System.model;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import java.time.Instant;
import java.util.Objects;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "machine_events", indexes = {
        @Index(name = "idx_machine_time", columnList = "machineId, eventTime"),
        @Index(name = "idx_event_time", columnList = "eventTime")
})
@DynamicUpdate
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MachineEvent {

    @Id
    @Column(nullable = false, unique = true)
    private String eventId;

    @Column(nullable = false)
    private Instant eventTime;

    @Column(nullable = false)
    private Instant receivedTime;

    @Column(nullable = false)
    private String machineId;

    @Column(nullable = false)
    private String factoryId;

    private long durationMs;

    private int defectCount; // -1 for unknown

    @Version
    private Long version;

    public boolean hasSamePayload(MachineEvent other) {
        if (other == null)
            return false;
        return this.durationMs == other.durationMs &&
                this.defectCount == other.defectCount &&
                Objects.equals(this.eventTime, other.eventTime) &&
                Objects.equals(this.machineId, other.machineId) &&
                Objects.equals(this.factoryId, other.factoryId);
    }
}
