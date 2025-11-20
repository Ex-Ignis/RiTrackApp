package es.hargos.ritrack.repository;

import es.hargos.ritrack.entity.UserCityAssignmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repositorio para gestionar asignaciones de ciudades a usuarios.
 * Proporciona métodos para consultar, crear y eliminar asignaciones.
 *
 * Casos de uso:
 * - Obtener ciudades asignadas a un usuario
 * - Asignar nuevas ciudades a un usuario
 * - Eliminar ciudades específicas o todas las asignaciones
 * - Verificar si un usuario tiene restricciones de ciudades
 * - Listar usuarios con asignaciones (para panel de admin)
 *
 * @author RiTrack Team
 * @version 2.1.0
 * @since 2025-11-19
 */
@Repository
public interface UserCityAssignmentRepository extends JpaRepository<UserCityAssignmentEntity, Long> {

    /**
     * Encuentra todas las asignaciones de ciudades para un usuario específico.
     *
     * @param userId ID del usuario
     * @return Lista de asignaciones (puede estar vacía si no tiene restricciones)
     *
     * Ejemplo:
     * <pre>
     * List<UserCityAssignmentEntity> assignments =
     *     repository.findByUserId(123L);
     * // Retorna: [Madrid, Barcelona] si tiene esas ciudades asignadas
     * // Retorna: [] si puede ver todas las ciudades
     * </pre>
     */
    List<UserCityAssignmentEntity> findByUserId(Long userId);

    /**
     * Elimina una asignación específica de ciudad para un usuario.
     * Útil para quitar una ciudad sin afectar las demás.
     *
     * @param userId ID del usuario
     * @param cityId ID de la ciudad a eliminar
     *
     * Nota: Debe usarse dentro de una transacción (@Transactional)
     */
    @Modifying
    void deleteByUserIdAndCityId(Long userId, Long cityId);

    /**
     * Elimina todas las asignaciones de un usuario.
     * Usado cuando se quiere resetear las restricciones de un usuario
     * para que pueda ver todas las ciudades nuevamente.
     *
     * @param userId ID del usuario
     *
     * Nota: Debe usarse dentro de una transacción (@Transactional)
     */
    @Modifying
    void deleteByUserId(Long userId);

    /**
     * Verifica si un usuario tiene alguna ciudad asignada.
     * Útil para determinar si aplicar filtro o mostrar todas las ciudades.
     *
     * @param userId ID del usuario
     * @return true si tiene al menos una ciudad asignada, false si puede ver todas
     *
     * Ejemplo de uso en servicio:
     * <pre>
     * if (repository.existsByUserId(userId)) {
     *     // Aplicar filtro de ciudades
     *     List<Long> cityIds = getUserCityIds(userId);
     *     filter.setCityIds(cityIds);
     * } else {
     *     // Usuario puede ver todas las ciudades (backward compatibility)
     * }
     * </pre>
     */
    boolean existsByUserId(Long userId);

    /**
     * Obtiene solo los IDs de ciudades asignadas a un usuario.
     * Optimizado para no cargar toda la entidad cuando solo se necesitan los IDs.
     *
     * @param userId ID del usuario
     * @return Lista de IDs de ciudades (List<Long>)
     *
     * Ejemplo:
     * <pre>
     * List<Long> cityIds = repository.findCityIdsByUserId(123L);
     * // Retorna: [902, 804] (Madrid, Barcelona)
     * </pre>
     */
    @Query("SELECT u.cityId FROM UserCityAssignmentEntity u WHERE u.userId = :userId")
    List<Long> findCityIdsByUserId(@Param("userId") Long userId);

    /**
     * Obtiene todos los IDs de usuarios que tienen al menos una ciudad asignada.
     * Útil para el panel de administración para listar usuarios con restricciones.
     *
     * @return Lista de IDs únicos de usuarios con asignaciones
     *
     * Ejemplo de uso en panel de admin:
     * <pre>
     * List<Long> usersWithRestrictions = repository.findAllUserIdsWithAssignments();
     * // Retorna: [123, 456]
     * </pre>
     */
    @Query("SELECT DISTINCT u.userId FROM UserCityAssignmentEntity u")
    List<Long> findAllUserIdsWithAssignments();

    /**
     * Verifica si existe una asignación específica de ciudad para un usuario.
     * Útil para evitar duplicados antes de crear una asignación.
     *
     * @param userId ID del usuario
     * @param cityId ID de la ciudad
     * @return true si la asignación ya existe, false en caso contrario
     */
    boolean existsByUserIdAndCityId(Long userId, Long cityId);

    /**
     * Cuenta cuántas ciudades tiene asignadas un usuario.
     *
     * @param userId ID del usuario
     * @return Número de ciudades asignadas
     */
    @Query("SELECT COUNT(u) FROM UserCityAssignmentEntity u WHERE u.userId = :userId")
    long countByUserId(@Param("userId") Long userId);

    /**
     * Obtiene todas las asignaciones para múltiples usuarios.
     * Útil para cargar asignaciones de varios usuarios de una vez.
     *
     * @param userIds Lista de IDs de usuarios
     * @return Lista de todas las asignaciones
     */
    List<UserCityAssignmentEntity> findByUserIdIn(List<Long> userIds);
}
