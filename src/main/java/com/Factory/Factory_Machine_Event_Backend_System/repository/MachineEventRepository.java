package com.Factory.Factory_Machine_Event_Backend_System.repository;

import com.Factory.Factory_Machine_Event_Backend_System.model.MachineEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface MachineEventRepository extends JpaRepository<MachineEvent, String> {

        @Query("SELECT e FROM MachineEvent e WHERE e.machineId = :machineId AND e.eventTime >= :start AND e.eventTime < :end")
        List<MachineEvent> findByMachineIdAndTimeRange(
                        @Param("machineId") String machineId,
                        @Param("start") Instant start,
                        @Param("end") Instant end);

        @Query("SELECT e.machineId as lineId, " +
                        "SUM(CASE WHEN e.defectCount = -1 THEN 0 ELSE e.defectCount END) as totalDefects, " +
                        "COUNT(e) as eventCount " +
                        "FROM MachineEvent e " +
                        "WHERE e.eventTime >= :start AND e.eventTime < :end " +
                        "GROUP BY e.machineId " +
                        "ORDER BY SUM(CASE WHEN e.defectCount = -1 THEN 0 ELSE e.defectCount END) DESC")
        List<DefectLineStats> findTopDefectLines(
                        @Param("start") Instant start,
                        @Param("end") Instant end);

        @Query("SELECT COALESCE(SUM(e.defectCount), 0) FROM MachineEvent e WHERE e.defectCount >= 0")
        long sumKnownDefects();
}
