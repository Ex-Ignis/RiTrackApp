package es.hargos.ritrack.repository;

import es.hargos.ritrack.entity.RiderMetricsCsvEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for rider metrics imported from CSV files.
 *
 * IMPORTANT: This repository operates on tenant-specific schemas.
 * Make sure to set the schema context before using these queries.
 */
@Repository
public interface RiderMetricsCsvRepository extends JpaRepository<RiderMetricsCsvEntity, Long> {

    Optional<RiderMetricsCsvEntity> findByEmployeeIdAndDate(String employeeId, LocalDate date);

    List<RiderMetricsCsvEntity> findByEmployeeIdAndDateBetween(
            String employeeId,
            LocalDate startDate,
            LocalDate endDate
    );

    List<RiderMetricsCsvEntity> findByDateBetween(LocalDate startDate, LocalDate endDate);

    List<RiderMetricsCsvEntity> findByCityIdAndDateBetween(
            Integer cityId,
            LocalDate startDate,
            LocalDate endDate
    );

    @Query("SELECT m FROM RiderMetricsCsvEntity m WHERE m.employeeId = :employeeId ORDER BY m.date DESC")
    List<RiderMetricsCsvEntity> findLatestMetricsByEmployeeId(@Param("employeeId") String employeeId);
}
