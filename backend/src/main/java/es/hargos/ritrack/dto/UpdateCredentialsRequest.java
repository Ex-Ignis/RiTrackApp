package es.hargos.ritrack.dto;

import lombok.Data;

/**
 * DTO para actualización de credenciales Glovo de un tenant.
 * Todos los campos son opcionales (null = no se actualiza).
 *
 * Al menos uno de los campos debe ser no-null para que la actualización proceda.
 *
 * IMPORTANTE: Este endpoint valida las nuevas credenciales con la API de Glovo
 * antes de guardarlas. Si la validación falla, no se actualiza nada.
 */
@Data
public class UpdateCredentialsRequest {

    /**
     * Client ID de Glovo (OAuth2)
     * null = mantener el existente
     */
    private String clientId;

    /**
     * Key ID de Glovo (para firma JWT)
     * null = mantener el existente
     */
    private String keyId;

    /**
     * Company ID de Glovo
     * null = mantener el existente
     */
    private Integer companyId;

    /**
     * Contract ID de Glovo
     * null = mantener el existente
     */
    private Integer contractId;

    /**
     * NOTA: El archivo .pem se envía como MultipartFile separado,
     * no en este DTO JSON.
     */

    /**
     * Verifica si al menos un campo fue enviado para actualizar
     */
    public boolean hasAnyUpdate() {
        return clientId != null ||
               keyId != null ||
               companyId != null ||
               contractId != null;
    }
}
