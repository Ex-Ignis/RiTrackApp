package es.hargos.ritrack.repository;

import es.hargos.ritrack.entity.RiderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for rider entities.
 *
 * IMPORTANT: This repository operates on tenant-specific schemas.
 * Queries will execute against the schema set in the current persistence context.
 */
@Repository
public interface RiderRepository extends JpaRepository<RiderEntity, Long> {

    Optional<RiderEntity> findByEmployeeId(String employeeId);

    List<RiderEntity> findByCityId(Integer cityId);

    List<RiderEntity> findByStatus(String status);

    List<RiderEntity> findByCityIdAndStatus(Integer cityId, String status);

    @Query("SELECT r FROM RiderEntity r WHERE r.status IN ('WORKING', 'READY', 'AVAILABLE')")
    List<RiderEntity> findActiveRiders();

    @Query("SELECT r FROM RiderEntity r WHERE r.status = 'NOT_WORKING'")
    List<RiderEntity> findInactiveRiders();

    @Query("SELECT r FROM RiderEntity r WHERE r.cityId = :cityId AND r.status IN ('WORKING', 'READY', 'AVAILABLE')")
    List<RiderEntity> findActiveRidersByCity(@Param("cityId") Integer cityId);

    boolean existsByEmployeeId(String employeeId);

    boolean existsByEmail(String email);

    Optional<RiderEntity> findByEmail(String email);

    @Query("SELECT COUNT(r) FROM RiderEntity r")
    long countAllRiders();

    @Query("SELECT COUNT(r) FROM RiderEntity r WHERE r.cityId = :cityId")
    long countRidersByCity(@Param("cityId") Integer cityId);
}