package es.hargos.ritrack.repository;

import es.hargos.ritrack.entity.RiderMetricsDailyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface RiderMetricsDailyRepository extends JpaRepository<RiderMetricsDailyEntity, Long> {

    /**
     * Encuentra métricas diarias de un rider en una fecha específica.
     */
    Optional<RiderMetricsDailyEntity> findByRiderIdAndDay(Integer riderId, LocalDate day);

    /**
     * Encuentra todas las métricas de un rider.
     */
    List<RiderMetricsDailyEntity> findByRiderId(Integer riderId);

    /**
     * Encuentra métricas entre un rango de fechas.
     */
    List<RiderMetricsDailyEntity> findByDayBetween(LocalDate startDate, LocalDate endDate);

    /**
     * Encuentra métricas de un rider entre un rango de fechas.
     */
    List<RiderMetricsDailyEntity> findByRiderIdAndDayBetween(Integer riderId, LocalDate startDate, LocalDate endDate);

    /**
     * Elimina métricas de un rider en una fecha específica.
     */
    void deleteByRiderIdAndDay(Integer riderId, LocalDate day);

    /**
     * Verifica si existen métricas para un rider en una fecha.
     */
    boolean existsByRiderIdAndDay(Integer riderId, LocalDate day);
}
