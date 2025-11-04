package es.hargos.ritrack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * DTO para configuración inicial de un tenant durante el proceso de onboarding.
 * Contiene las credenciales de Glovo API y configuraciones específicas del tenant.
 */
@Data
public class OnboardingDto {

    // ===== GLOVO API CREDENTIALS =====

    @NotBlank(message = "Client ID es requerido")
    private String clientId;

    @NotBlank(message = "Key ID es requerido")
    private String keyId;

    /**
     * Company ID de Glovo (opcional - se auto-detecta desde la API)
     */
    private Integer companyId;

    /**
     * Contract ID de Glovo (opcional - se auto-detecta si hay un solo contrato activo)
     */
    private Integer contractId;

    // NOTA: El archivo .pem se envía como MultipartFile en el request
    // y se procesa directamente en el controller

    // ===== TENANT SETTINGS =====

    /**
     * IDs de ciudades activas para monitoreo de riders.
     * Ejemplo: [902, 804, 882] → Madrid, Barcelona, Leganés
     */
    private List<Integer> activeCityIds;

    /**
     * Dominio para emails automáticos de riders.
     * Ejemplo: "arendeltech" → arendeltech+10001@gmail.com
     */
    @NotBlank(message = "Email domain es requerido")
    private String emailDomain;

    /**
     * Base de email para riders.
     * Ejemplo: "gmail.com"
     */
    @NotBlank(message = "Email base es requerido")
    private String emailBase;

    /**
     * Prefijo para nombres automáticos de riders.
     * Ejemplo: "Arendel" → "Arendel 10001"
     */
    @NotBlank(message = "Name base es requerido")
    private String nameBase;

    /**
     * Tipos de vehículo por defecto para nuevos riders.
     * Ejemplo: [5, 1, 3, 2] → Bike, Car, Motorbike, Scooter
     */
    private List<Integer> defaultVehicleTypeIds;

    // ===== OAUTH2 ENDPOINTS (OPCIONAL - USAR DEFAULTS) =====

    /**
     * URL de audiencia OAuth2 (default: https://sts.deliveryhero.io)
     */
    private String audienceUrl;

    /**
     * URL de token OAuth2 (default: https://sts.dh-auth.io/oauth2/token)
     */
    private String tokenUrl;

    /**
     * Base URL de Glovo Rooster API (default: https://gv-es.usehurrier.com/api/rooster)
     */
    private String roosterBaseUrl;

    /**
     * Base URL de Glovo Live API (default: https://gv-es.usehurrier.com/api/rider-live-operations)
     */
    private String liveBaseUrl;
}
