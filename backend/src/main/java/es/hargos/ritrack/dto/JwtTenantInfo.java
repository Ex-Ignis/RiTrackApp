package es.hargos.ritrack.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO para información de tenants extraída del JWT de HargosAuth.
 *
 * Estructura del JWT:
 * {
 *   "tenants": [
 *     {
 *       "tenantId": 1,
 *       "tenantName": "Arendel",
 *       "appName": "riders",
 *       "role": "TENANT_ADMIN"
 *     }
 *   ]
 * }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JwtTenantInfo {

    /**
     * ID del tenant en HargosAuth (auth.tenants.id)
     */
    private Long tenantId;

    /**
     * Nombre del tenant (ej: "Arendel", "Entregalia")
     */
    private String tenantName;

    /**
     * Nombre de la aplicación (ej: "riders")
     */
    private String appName;

    /**
     * Rol del usuario en este tenant (ej: "SUPER_ADMIN", "TENANT_ADMIN", "USER")
     */
    private String role;
}
