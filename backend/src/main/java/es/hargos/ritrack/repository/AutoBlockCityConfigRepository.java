package es.hargos.ritrack.repository;

import es.hargos.ritrack.entity.AutoBlockCityConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repositorio para gestionar la configuración de auto-bloqueo por ciudad.
 *
 * MULTI-TENANT: Opera sobre el schema del tenant actual (configurado por TenantContext).
 */
@Repository
public interface AutoBlockCityConfigRepository extends JpaRepository<AutoBlockCityConfigEntity, Long> {

    /**
     * Busca la configuración de auto-bloqueo para una ciudad específica.
     *
     * @param cityId ID de la ciudad de Glovo
     * @return Optional con la configuración si existe
     */
    Optional<AutoBlockCityConfigEntity> findByCityId(Integer cityId);

    /**
     * Encuentra todas las configuraciones con auto-bloqueo habilitado.
     *
     * @return Lista de configuraciones habilitadas
     */
    List<AutoBlockCityConfigEntity> findByEnabledTrue();

    /**
     * Encuentra todas las configuraciones ordenadas por cityId.
     *
     * @return Lista de todas las configuraciones
     */
    @Query("SELECT c FROM AutoBlockCityConfigEntity c ORDER BY c.cityId ASC")
    List<AutoBlockCityConfigEntity> findAllOrderedByCityId();

    /**
     * Verifica si existe configuración para una ciudad específica.
     *
     * @param cityId ID de la ciudad
     * @return true si existe configuración
     */
    boolean existsByCityId(Integer cityId);
}
