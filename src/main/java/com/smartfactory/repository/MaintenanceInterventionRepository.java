package com.smartfactory.repository;

import com.smartfactory.entity.InterventionStatus;
import com.smartfactory.entity.MaintenanceIntervention;
import com.smartfactory.entity.MaintenanceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface MaintenanceInterventionRepository extends MongoRepository<MaintenanceIntervention, String> {

    List<MaintenanceIntervention> findByMachineId(String machineId);

    Page<MaintenanceIntervention> findByMachineId(String machineId, Pageable pageable);

    Page<MaintenanceIntervention> findByTechnicianId(String technicianId, Pageable pageable);

    Page<MaintenanceIntervention> findByTechnicianIdsContaining(String technicianId, Pageable pageable);

    Page<MaintenanceIntervention> findByTechnicianIdsContainingOrTechnicianId(String technicianId1, String technicianId2, Pageable pageable);

    List<MaintenanceIntervention> findByTechnicianIdsContaining(String technicianId);

    Page<MaintenanceIntervention> findByStatus(InterventionStatus status, Pageable pageable);

    Page<MaintenanceIntervention> findByType(MaintenanceType type, Pageable pageable);

    Page<MaintenanceIntervention> findByMachineIdAndStatus(String machineId, InterventionStatus status, Pageable pageable);

    Page<MaintenanceIntervention> findByMachineIdAndType(String machineId, MaintenanceType type, Pageable pageable);

    Page<MaintenanceIntervention> findByCreatedAtBetween(Instant from, Instant to, Pageable pageable);

    Page<MaintenanceIntervention> findByMachineIdAndCreatedAtBetween(String machineId, Instant from, Instant to, Pageable pageable);

    long countByStatus(InterventionStatus status);

    long countByMachineIdAndStatus(String machineId, InterventionStatus status);
}
