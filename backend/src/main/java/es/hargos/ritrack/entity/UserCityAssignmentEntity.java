package es.hargos.ritrack.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entidad que mapea la asignación de ciudades a usuarios dentro de un tenant.
 * Permite control granular de qué ciudades puede ver cada usuario.
 *
 * Ejemplo de uso:
 * - Usuario juan@arendel.com asignado a Madrid (city_id=902) y Barcelona (city_id=804)
 * - Usuario maria@arendel.com asignada solo a Valencia (city_id=882)
 * - Usuario sin registros puede ver todas las ciudades (backward compatibility)
 *
 * @author RiTrack Team
 * @version 2.1.0
 * @since 2025-11-19
 */
@Entity
@Table(name = "user_city_assignments")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserCityAssignmentEntity {

    /**
     * ID único de la asignación
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * ID del usuario desde HargosAuth (auth.users.id)
     * Más eficiente que usar email (BIGINT vs VARCHAR).
     * El user_id se obtiene del JWT en TenantContext.getUserId()
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * ID de la ciudad de Glovo
     * Corresponde a los IDs de ciudades obtenidos de la API de Glovo.
     *
     * Ejemplos:
     * - 902: Madrid
     * - 804: Barcelona
     * - 882: Valencia
     */
    @Column(name = "city_id", nullable = false)
    private Long cityId;

    /**
     * ID del TENANT_ADMIN que realizó la asignación
     * Útil para auditoría y tracking de cambios.
     */
    @Column(name = "assigned_by_user_id")
    private Long assignedByUserId;

    /**
     * Fecha y hora de creación del registro
     * Se establece automáticamente al crear la asignación.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Hook ejecutado antes de persistir la entidad
     * Establece la fecha de creación si no está definida.
     */
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /**
     * Constructor de conveniencia para crear asignaciones
     *
     * @param userId ID del usuario
     * @param cityId ID de la ciudad
     * @param assignedByUserId ID del administrador que asigna
     */
    public UserCityAssignmentEntity(Long userId, Long cityId, Long assignedByUserId) {
        this.userId = userId;
        this.cityId = cityId;
        this.assignedByUserId = assignedByUserId;
    }
}
