package es.hargos.ritrack.repository;

import es.hargos.ritrack.entity.RiderBlockStatusEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repositorio para gestionar el estado de bloqueo de riders.
 *
 * MULTI-TENANT: Opera sobre el schema del tenant actual (configurado por TenantContext).
 */
@Repository
public interface RiderBlockStatusRepository extends JpaRepository<RiderBlockStatusEntity, String> {

    /**
     * Busca el estado de bloqueo de un rider específico.
     */
    Optional<RiderBlockStatusEntity> findByEmployeeId(String employeeId);

    /**
     * Encuentra todos los riders con auto-bloqueo activo.
     */
    List<RiderBlockStatusEntity> findByIsAutoBlocked(Boolean isAutoBlocked);

    /**
     * Encuentra todos los riders con bloqueo manual activo.
     */
    List<RiderBlockStatusEntity> findByIsManualBlocked(Boolean isManualBlocked);

    /**
     * Encuentra todos los riders actualmente auto-bloqueados.
     * Ordenados por fecha de bloqueo más reciente primero.
     */
    @Query("SELECT r FROM RiderBlockStatusEntity r WHERE r.isAutoBlocked = true " +
           "ORDER BY r.autoBlockedAt DESC")
    List<RiderBlockStatusEntity> findCurrentlyAutoBlocked();

    /**
     * Encuentra riders que tienen cualquier tipo de bloqueo activo.
     */
    @Query("SELECT r FROM RiderBlockStatusEntity r WHERE r.isAutoBlocked = true OR r.isManualBlocked = true")
    List<RiderBlockStatusEntity> findAllBlocked();
}
