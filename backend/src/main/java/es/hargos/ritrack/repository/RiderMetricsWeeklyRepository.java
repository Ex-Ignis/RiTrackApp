package es.hargos.ritrack.repository;

import es.hargos.ritrack.entity.RiderMetricsWeeklyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RiderMetricsWeeklyRepository extends JpaRepository<RiderMetricsWeeklyEntity, Long> {

    /**
     * Encuentra métricas semanales de un rider en una semana específica.
     */
    Optional<RiderMetricsWeeklyEntity> findByRiderIdAndWeek(Integer riderId, String week);

    /**
     * Encuentra todas las métricas de un rider.
     */
    List<RiderMetricsWeeklyEntity> findByRiderId(Integer riderId);

    /**
     * Encuentra métricas de una semana específica.
     */
    List<RiderMetricsWeeklyEntity> findByWeek(String week);

    /**
     * Elimina métricas de un rider en una semana específica.
     */
    void deleteByRiderIdAndWeek(Integer riderId, String week);

    /**
     * Verifica si existen métricas para un rider en una semana.
     */
    boolean existsByRiderIdAndWeek(Integer riderId, String week);

    /**
     * Encuentra las últimas N semanas de métricas para un rider.
     */
    List<RiderMetricsWeeklyEntity> findTop10ByRiderIdOrderByWeekDesc(Integer riderId);

    /**
     * Encuentra métricas de un rider entre un rango de semanas.
     */
    List<RiderMetricsWeeklyEntity> findByRiderIdAndWeekBetween(Integer riderId, String startWeek, String endWeek);
}
