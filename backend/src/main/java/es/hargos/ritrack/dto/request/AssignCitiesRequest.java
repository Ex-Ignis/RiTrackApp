package es.hargos.ritrack.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO para asignar ciudades a un usuario.
 * Usado por TENANT_ADMIN para configurar qué ciudades puede ver cada usuario.
 *
 * Ejemplo de JSON request:
 * <pre>
 * {
 *   "userId": 123,
 *   "cityIds": [902, 804]  // Madrid, Barcelona
 * }
 * </pre>
 *
 * Comportamiento:
 * - Reemplaza todas las ciudades existentes del usuario
 * - Si cityIds está vacío [], el usuario pierde todas las asignaciones (ve todas las ciudades)
 * - Solo TENANT_ADMIN puede usar este endpoint
 *
 * @author RiTrack Team
 * @version 2.1.0
 * @since 2025-11-19
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssignCitiesRequest {

    /**
     * ID del usuario al que se le asignarán las ciudades.
     * Debe ser un usuario válido dentro del tenant actual.
     * Obtenido de HargosAuth (auth.users.id)
     */
    @NotNull(message = "El ID del usuario es requerido")
    @Positive(message = "El ID del usuario debe ser positivo")
    private Long userId;

    /**
     * Lista de IDs de ciudades a asignar.
     * Debe contener al menos una ciudad.
     *
     * Ejemplos de city_id:
     * - 902: Madrid
     * - 804: Barcelona
     * - 882: Valencia
     * - 879: Sevilla
     */
    @NotEmpty(message = "Debe asignar al menos una ciudad")
    private List<Long> cityIds;
}
