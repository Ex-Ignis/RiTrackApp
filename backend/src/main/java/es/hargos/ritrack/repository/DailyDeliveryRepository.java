package es.hargos.ritrack.repository;

import es.hargos.ritrack.entity.DailyDeliveryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyDeliveryRepository extends JpaRepository<DailyDeliveryEntity, Long> {

    /**
     * Encuentra entregas diarias de un rider en una fecha específica.
     */
    Optional<DailyDeliveryEntity> findByRiderIdAndDate(String riderId, LocalDate date);

    /**
     * Encuentra todas las entregas de un rider.
     */
    List<DailyDeliveryEntity> findByRiderId(String riderId);

    /**
     * Encuentra entregas entre un rango de fechas.
     */
    List<DailyDeliveryEntity> findByDateBetween(LocalDate startDate, LocalDate endDate);

    /**
     * Encuentra entregas de un rider entre un rango de fechas.
     */
    List<DailyDeliveryEntity> findByRiderIdAndDateBetween(String riderId, LocalDate startDate, LocalDate endDate);

    /**
     * Elimina entregas de un rider en una fecha específica.
     */
    void deleteByRiderIdAndDate(String riderId, LocalDate date);

    /**
     * Verifica si existen entregas para un rider en una fecha.
     */
    boolean existsByRiderIdAndDate(String riderId, LocalDate date);

    /**
     * Encuentra las últimas N entregas de un rider.
     */
    List<DailyDeliveryEntity> findTop30ByRiderIdOrderByDateDesc(String riderId);
}
