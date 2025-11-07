package es.hargos.ritrack.dto;

import lombok.Data;

import java.util.List;

/**
 * DTO para actualización parcial de configuración de tenant.
 * Todos los campos son opcionales (null = no se actualiza).
 *
 * Este DTO NO incluye credenciales sensibles (clientId, keyId, pemFile).
 * Para actualizar credenciales, usar el endpoint PUT /api/v1/tenant/credentials.
 */
@Data
public class UpdateSettingsRequest {

    /**
     * IDs de ciudades activas para monitoreo de riders.
     * Ejemplo: [902, 804, 882] → Madrid, Barcelona, Leganés
     * null = no actualizar
     */
    private List<Integer> activeCityIds;

    /**
     * Dominio para emails automáticos de riders.
     * Ejemplo: "entregalia" → entregalia+10001@gmail.com
     * null = no actualizar
     */
    private String emailDomain;

    /**
     * Base de email para riders.
     * Ejemplo: "gmail.com"
     * null = no actualizar
     */
    private String emailBase;

    /**
     * Prefijo para nombres automáticos de riders.
     * Ejemplo: "Entregalia" → "Entregalia 10001"
     * null = no actualizar
     */
    private String nameBase;

    /**
     * Tipos de vehículo por defecto para nuevos riders.
     * Ejemplo: [5, 1, 3, 2] → Bike, Car, Motorbike, Scooter
     * null = no actualizar
     */
    private List<Integer> defaultVehicleTypeIds;

    /**
     * Company ID de Glovo
     * null = no actualizar
     */
    private Integer companyId;

    /**
     * Contract ID de Glovo
     * null = no actualizar
     */
    private Integer contractId;

    /**
     * Base URL de Glovo Rooster API
     * null = no actualizar
     */
    private String roosterBaseUrl;

    /**
     * Base URL de Glovo Live API
     * null = no actualizar
     */
    private String liveBaseUrl;

    /**
     * Verifica si al menos un campo fue enviado para actualizar
     */
    public boolean hasAnyUpdate() {
        return activeCityIds != null ||
               emailDomain != null ||
               emailBase != null ||
               nameBase != null ||
               defaultVehicleTypeIds != null ||
               companyId != null ||
               contractId != null ||
               roosterBaseUrl != null ||
               liveBaseUrl != null;
    }
}
