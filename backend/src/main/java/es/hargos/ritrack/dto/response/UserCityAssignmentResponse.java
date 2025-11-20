package es.hargos.ritrack.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * DTO de respuesta con las ciudades asignadas a un usuario.
 * Incluye información detallada de las ciudades para mostrar en la UI.
 *
 * Ejemplo de JSON response:
 * <pre>
 * {
 *   "userId": 123,
 *   "userEmail": "juan@arendel.com",
 *   "fullName": "Juan Pérez",
 *   "assignedCityIds": [902, 804],
 *   "assignedCities": [
 *     {
 *       "cityId": 902,
 *       "cityName": "Madrid",
 *       "countryCode": "ES"
 *     },
 *     {
 *       "cityId": 804,
 *       "cityName": "Barcelona",
 *       "countryCode": "ES"
 *     }
 *   ]
 * }
 * </pre>
 *
 * @author RiTrack Team
 * @version 2.1.0
 * @since 2025-11-19
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserCityAssignmentResponse {

    /**
     * ID del usuario desde HargosAuth
     */
    private Long userId;

    /**
     * Email del usuario (opcional, para display en UI)
     * Puede obtenerse del JWT o de HargosAuth
     */
    private String userEmail;

    /**
     * Nombre completo del usuario (opcional)
     * Puede obtenerse del JWT o de HargosAuth
     */
    private String fullName;

    /**
     * Lista de IDs de ciudades asignadas
     * Si está vacía, el usuario puede ver todas las ciudades
     */
    private List<Long> assignedCityIds = new ArrayList<>();

    /**
     * Información detallada de las ciudades asignadas
     * Incluye nombre y código de país para mostrar en UI
     */
    private List<CityInfo> assignedCities = new ArrayList<>();

    /**
     * Constructor de conveniencia para respuestas simples
     *
     * @param userId ID del usuario
     * @param assignedCityIds IDs de ciudades asignadas
     */
    public UserCityAssignmentResponse(Long userId, List<Long> assignedCityIds) {
        this.userId = userId;
        this.assignedCityIds = assignedCityIds;
        this.assignedCities = new ArrayList<>();
    }

    /**
     * Constructor con userId y email
     *
     * @param userId ID del usuario
     * @param userEmail Email del usuario
     * @param assignedCityIds IDs de ciudades asignadas
     */
    public UserCityAssignmentResponse(Long userId, String userEmail, List<Long> assignedCityIds) {
        this.userId = userId;
        this.userEmail = userEmail;
        this.assignedCityIds = assignedCityIds;
        this.assignedCities = new ArrayList<>();
    }

    /**
     * Clase interna para información de ciudad
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CityInfo {
        /**
         * ID de la ciudad de Glovo
         */
        private Long cityId;

        /**
         * Nombre de la ciudad
         * Ejemplo: "Madrid", "Barcelona"
         */
        private String cityName;

        /**
         * Código de país ISO
         * Ejemplo: "ES", "PT"
         */
        private String countryCode;
    }
}
