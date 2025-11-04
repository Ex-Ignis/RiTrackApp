package es.hargos.ritrack.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO para indicar el estado del onboarding de un tenant.
 * Se usa para verificar si un tenant necesita configuración inicial.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OnboardingStatusDto {

    /**
     * Indica si el tenant tiene credenciales Glovo configuradas y está listo para usar.
     */
    private boolean configured;

    /**
     * Nombre del tenant.
     */
    private String tenantName;

    /**
     * Indica si el tenant necesita completar el proceso de onboarding.
     */
    private boolean needsSetup;

    /**
     * Mensaje descriptivo del estado actual.
     */
    private String message;

    /**
     * Nombre del schema PostgreSQL del tenant.
     */
    private String schemaName;

    /**
     * Constructor simplificado para tenant configurado.
     */
    public static OnboardingStatusDto configured(String tenantName, String schemaName) {
        return new OnboardingStatusDto(
                true,
                tenantName,
                false,
                "Tenant configurado correctamente",
                schemaName
        );
    }

    /**
     * Constructor simplificado para tenant que necesita setup.
     */
    public static OnboardingStatusDto needsSetup(String tenantName, String schemaName) {
        return new OnboardingStatusDto(
                false,
                tenantName,
                true,
                "Tenant requiere configuración de credenciales Glovo",
                schemaName
        );
    }
}
