package es.hargos.ritrack.service;

import es.hargos.ritrack.client.HargosAuthClient;
import es.hargos.ritrack.dto.request.AssignCitiesRequest;
import es.hargos.ritrack.dto.response.UserCityAssignmentResponse;
import es.hargos.ritrack.entity.UserCityAssignmentEntity;
import es.hargos.ritrack.repository.UserCityAssignmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Servicio para gestionar asignaciones de ciudades a usuarios.
 * Implementa la lógica de negocio para control granular de acceso por ciudad.
 *
 * Flujo de uso:
 * 1. TENANT_ADMIN asigna ciudades a un usuario
 * 2. Usuario inicia sesión y solicita datos
 * 3. Sistema verifica ciudades asignadas usando userId
 * 4. Si tiene asignaciones → filtra automáticamente
 * 5. Si no tiene asignaciones → muestra todo (backward compatibility)
 *
 * @author RiTrack Team
 * @version 2.1.0
 * @since 2025-11-19
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserCityService {

    private final UserCityAssignmentRepository userCityRepository;
    private final HargosAuthClient hargosAuthClient;

    /**
     * Asigna ciudades a un usuario (reemplaza las existentes).
     *
     * Este método:
     * 1. Elimina todas las asignaciones anteriores del usuario
     * 2. Crea nuevas asignaciones con las ciudades proporcionadas
     * 3. Registra quién realizó la asignación (para auditoría)
     *
     * @param request Datos de asignación (userId + cityIds)
     * @param assignedByUserId ID del TENANT_ADMIN que realiza la asignación
     * @throws IllegalArgumentException si cityIds está vacío
     *
     * Ejemplo de uso:
     * <pre>
     * AssignCitiesRequest request = new AssignCitiesRequest();
     * request.setUserId(123L);
     * request.setCityIds(Arrays.asList(902L, 804L)); // Madrid, Barcelona
     *
     * userCityService.assignCitiesToUser(request, 456L); // Admin ID
     * </pre>
     */
    @Transactional
    public void assignCitiesToUser(AssignCitiesRequest request, Long assignedByUserId) {
        Long userId = request.getUserId();
        List<Long> cityIds = request.getCityIds();

        log.info("Asignando {} ciudades al usuario ID {} (asignado por user ID: {})",
                cityIds.size(), userId, assignedByUserId);

        // 1. Eliminar asignaciones anteriores
        long deletedCount = countByUserId(userId);
        if (deletedCount > 0) {
            userCityRepository.deleteByUserId(userId);
            log.info("Eliminadas {} asignaciones anteriores del usuario ID {}", deletedCount, userId);
        }

        // 2. Crear nuevas asignaciones
        List<UserCityAssignmentEntity> assignments = cityIds.stream()
                .map(cityId -> new UserCityAssignmentEntity(userId, cityId, assignedByUserId))
                .collect(Collectors.toList());

        userCityRepository.saveAll(assignments);

        log.info("✅ Ciudades asignadas exitosamente: {} ciudades a usuario ID {}", cityIds.size(), userId);
    }

    /**
     * Obtiene los IDs de ciudades asignadas a un usuario.
     * Método optimizado que solo retorna IDs (no entidades completas).
     *
     * @param userId ID del usuario
     * @return Lista de IDs de ciudades (puede estar vacía)
     *
     * Uso típico en filtros:
     * <pre>
     * List<Long> userCityIds = userCityService.getUserCityIds(userId);
     * if (!userCityIds.isEmpty()) {
     *     filter.setCityIds(userCityIds); // Aplicar filtro
     * }
     * </pre>
     */
    public List<Long> getUserCityIds(Long userId) {
        List<Long> cityIds = userCityRepository.findCityIdsByUserId(userId);
        log.debug("Usuario ID {} tiene {} ciudades asignadas", userId, cityIds.size());
        return cityIds;
    }

    /**
     * Obtiene información detallada de ciudades asignadas a un usuario.
     * Incluye UserCityAssignmentResponse con IDs y metadata.
     *
     * @param userId ID del usuario
     * @return Response con información de ciudades
     *
     * Nota: Por ahora solo retorna IDs. En el futuro se puede enriquecer
     * con nombres de ciudades usando GlovoClient o caché de ciudades.
     */
    public UserCityAssignmentResponse getUserCities(Long userId) {
        List<Long> cityIds = getUserCityIds(userId);

        UserCityAssignmentResponse response = new UserCityAssignmentResponse();
        response.setUserId(userId);
        response.setAssignedCityIds(cityIds);
        response.setAssignedCities(new ArrayList<>()); // TODO: Enriquecer con nombres

        log.debug("Generado response para usuario ID {}: {} ciudades", userId, cityIds.size());
        return response;
    }

    /**
     * Obtiene ciudades con email incluido (para display en UI)
     *
     * @param userId ID del usuario
     * @param userEmail Email del usuario (para incluir en response)
     * @return Response con userId, email y ciudades
     */
    public UserCityAssignmentResponse getUserCitiesWithEmail(Long userId, String userEmail) {
        List<Long> cityIds = getUserCityIds(userId);

        UserCityAssignmentResponse response = new UserCityAssignmentResponse();
        response.setUserId(userId);
        response.setUserEmail(userEmail);
        response.setAssignedCityIds(cityIds);
        response.setAssignedCities(new ArrayList<>());

        return response;
    }

    /**
     * Verifica si un usuario tiene ciudades asignadas.
     * Método rápido para determinar si aplicar filtros o no.
     *
     * @param userId ID del usuario
     * @return true si tiene asignaciones, false si puede ver todas las ciudades
     *
     * Ejemplo de uso en RiderFilterService:
     * <pre>
     * if (userCityService.hasAnyCityAssigned(userId)) {
     *     // Usuario tiene restricciones → aplicar filtro
     *     List<Long> cities = userCityService.getUserCityIds(userId);
     *     applyFilter(cities);
     * } else {
     *     // Usuario sin restricciones → mostrar todo
     * }
     * </pre>
     */
    public boolean hasAnyCityAssigned(Long userId) {
        boolean hasAssignments = userCityRepository.existsByUserId(userId);
        log.debug("Usuario ID {} {} ciudades asignadas",
                userId, hasAssignments ? "tiene" : "no tiene");
        return hasAssignments;
    }

    /**
     * Elimina una ciudad específica de un usuario.
     * Útil para quitar una ciudad sin afectar las demás asignaciones.
     *
     * @param userId ID del usuario
     * @param cityId ID de la ciudad a eliminar
     */
    @Transactional
    public void removeCityFromUser(Long userId, Long cityId) {
        log.info("Eliminando ciudad {} del usuario ID {}", cityId, userId);

        if (!userCityRepository.existsByUserIdAndCityId(userId, cityId)) {
            log.warn("⚠️ No existe asignación de ciudad {} para usuario ID {}", cityId, userId);
            return;
        }

        userCityRepository.deleteByUserIdAndCityId(userId, cityId);
        log.info("✅ Ciudad {} eliminada del usuario ID {}", cityId, userId);
    }

    /**
     * Elimina todas las asignaciones de un usuario.
     * Después de esto, el usuario podrá ver todas las ciudades del tenant.
     *
     * @param userId ID del usuario
     */
    @Transactional
    public void removeAllCitiesFromUser(Long userId) {
        long count = countByUserId(userId);

        if (count == 0) {
            log.info("Usuario ID {} no tiene ciudades asignadas, nada que eliminar", userId);
            return;
        }

        log.info("Eliminando todas las ciudades del usuario ID {} ({} asignaciones)", userId, count);
        userCityRepository.deleteByUserId(userId);
        log.info("✅ Todas las ciudades eliminadas del usuario ID {}", userId);
    }

    /**
     * Lista TODOS los usuarios del tenant con sus ciudades asignadas.
     * Obtiene usuarios de HargosAuth y combina con asignaciones de ciudades.
     * Para panel de administración de TENANT_ADMIN.
     *
     * @param hargosTenantId ID del tenant en HargosAuth
     * @return Lista de TODOS los usuarios del tenant con sus ciudades (vacías si no tienen)
     *
     * Ejemplo de response:
     * <pre>
     * [
     *   {
     *     "userId": 123,
     *     "userEmail": "admin@empresa.com",
     *     "assignedCityIds": [902, 804]
     *   },
     *   {
     *     "userId": 456,
     *     "userEmail": "trabajador@empresa.com",
     *     "assignedCityIds": []  // Sin asignaciones = ve todas
     *   }
     * ]
     * </pre>
     */
    public List<UserCityAssignmentResponse> getAllUsersWithCities(Long hargosTenantId) {
        log.info("🔍 Obteniendo TODOS los usuarios del tenant {} desde HargosAuth", hargosTenantId);

        // 1. Obtener TODOS los usuarios del tenant desde HargosAuth
        List<HargosAuthClient.TenantUserResponse> tenantUsers = hargosAuthClient.getTenantUsers(hargosTenantId);

        if (tenantUsers.isEmpty()) {
            log.warn("⚠️ No se encontraron usuarios en HargosAuth para tenant {}", hargosTenantId);
            return new ArrayList<>();
        }

        log.info("📊 Encontrados {} usuarios en HargosAuth para tenant {}", tenantUsers.size(), hargosTenantId);

        // Log cada usuario recibido
        tenantUsers.forEach(user -> {
            log.info("  👤 Usuario desde HargosAuth: ID={}, Email={}, Role={}",
                user.getId(), user.getEmail(), user.getRole());
        });

        // 2. Obtener todas las asignaciones de ciudades existentes
        List<UserCityAssignmentEntity> allAssignments = userCityRepository.findAll();

        // Agrupar asignaciones por userId para búsqueda rápida
        Map<Long, List<Long>> assignmentsByUser = allAssignments.stream()
                .collect(Collectors.groupingBy(
                        UserCityAssignmentEntity::getUserId,
                        Collectors.mapping(UserCityAssignmentEntity::getCityId, Collectors.toList())
                ));

        // 3. Combinar: crear response para cada usuario con sus asignaciones (o vacías)
        List<UserCityAssignmentResponse> responses = tenantUsers.stream()
                .map(user -> {
                    UserCityAssignmentResponse response = new UserCityAssignmentResponse();
                    response.setUserId(user.getId());
                    response.setUserEmail(user.getEmail());

                    // Obtener ciudades asignadas de este usuario (o lista vacía)
                    List<Long> assignedCities = assignmentsByUser.getOrDefault(user.getId(), new ArrayList<>());
                    response.setAssignedCityIds(assignedCities);
                    response.setAssignedCities(new ArrayList<>());

                    return response;
                })
                .collect(Collectors.toList());

        log.debug("✅ Retornando {} usuarios con sus asignaciones de ciudades", responses.size());
        return responses;
    }

    /**
     * Cuenta cuántas ciudades tiene asignadas un usuario.
     *
     * @param userId ID del usuario
     * @return Número de ciudades asignadas
     */
    public long countByUserId(Long userId) {
        return userCityRepository.countByUserId(userId);
    }

    /**
     * Verifica si una ciudad específica está asignada a un usuario.
     *
     * @param userId ID del usuario
     * @param cityId ID de la ciudad
     * @return true si el usuario tiene esa ciudad asignada
     */
    public boolean isUserAssignedToCity(Long userId, Long cityId) {
        return userCityRepository.existsByUserIdAndCityId(userId, cityId);
    }

    /**
     * Añade ciudades adicionales a un usuario sin eliminar las existentes.
     * A diferencia de assignCitiesToUser, este método no reemplaza.
     *
     * @param userId ID del usuario
     * @param cityIds IDs de ciudades a añadir
     * @param assignedByUserId ID del admin
     */
    @Transactional
    public void addCitiesToUser(Long userId, List<Long> cityIds, Long assignedByUserId) {
        log.info("Añadiendo {} ciudades adicionales al usuario ID {}", cityIds.size(), userId);

        // Filtrar ciudades que ya están asignadas
        List<Long> newCityIds = cityIds.stream()
                .filter(cityId -> !userCityRepository.existsByUserIdAndCityId(userId, cityId))
                .collect(Collectors.toList());

        if (newCityIds.isEmpty()) {
            log.info("Todas las ciudades ya están asignadas al usuario ID {}", userId);
            return;
        }

        List<UserCityAssignmentEntity> newAssignments = newCityIds.stream()
                .map(cityId -> new UserCityAssignmentEntity(userId, cityId, assignedByUserId))
                .collect(Collectors.toList());

        userCityRepository.saveAll(newAssignments);
        log.info("✅ {} nuevas ciudades añadidas al usuario ID {}", newCityIds.size(), userId);
    }
}
