package es.hargos.ritrack.controller;

import es.hargos.ritrack.context.TenantContext;
import es.hargos.ritrack.dto.OnboardingDto;
import es.hargos.ritrack.dto.OnboardingStatusDto;
import es.hargos.ritrack.service.TenantOnboardingService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller para gestionar el proceso de onboarding de nuevos tenants.
 *
 * Endpoints:
 * - GET /status: Verifica si el tenant necesita configuración inicial
 * - POST /configure: Provisiona tenant con credenciales Glovo y configuraciones
 *
 * Flujo típico:
 * 1. Usuario compra RiTrack en HargosAuth → se crea entrada en public.tenants
 * 2. Usuario accede a RiTrack → frontend llama GET /status
 * 3. Si needsSetup=true → frontend muestra formulario de credenciales
 * 4. Usuario envía credenciales + .pem → POST /configure
 * 5. Backend provisiona automáticamente (valida, crea schema, inserta settings)
 * 6. Usuario ya puede usar RiTrack
 */
@RestController
@RequestMapping("/api/v1/tenant/onboarding")
public class TenantOnboardingController {

    private static final Logger logger = LoggerFactory.getLogger(TenantOnboardingController.class);

    private final TenantOnboardingService onboardingService;

    @Autowired
    public TenantOnboardingController(TenantOnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    /**
     * Verifica el estado del onboarding del tenant actual.
     *
     * Responde:
     * - configured=true si el tenant ya tiene credenciales y schema configurado
     * - needsSetup=true si el tenant necesita completar el onboarding
     *
     * @return Status del onboarding
     */
    @GetMapping("/status")
    public ResponseEntity<?> getOnboardingStatus() {
        try {
            // Obtener tenantId del contexto (desde JWT + X-Tenant-Id header)
            TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();

            if (tenantInfo == null || tenantInfo.getFirstTenantId() == null) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Tenant no encontrado");
                error.put("message", "No se pudo determinar el tenant desde el JWT y header X-Tenant-Id.");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
            }

            Long tenantId = tenantInfo.getFirstTenantId();
            logger.info("Verificando status de onboarding para tenant {}", tenantId);
            OnboardingStatusDto status = onboardingService.getOnboardingStatus(tenantId);
            return ResponseEntity.ok(status);

        } catch (IllegalArgumentException e) {
            logger.error("Tenant no encontrado: {}", e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", "Tenant inválido");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);

        } catch (Exception e) {
            logger.error("Error obteniendo status de onboarding: {}", e.getMessage(), e);
            Map<String, String> error = new HashMap<>();
            error.put("error", "Error interno");
            error.put("message", "Error verificando estado del tenant");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Provisiona un tenant completamente desde cero.
     *
     * Recibe:
     * - FormData con credenciales Glovo (client_id, key_id, company_id, contract_id)
     * - Archivo .pem con clave privada
     * - Configuraciones del tenant (ciudades activas, email domain, etc.)
     *
     * Proceso automático:
     * 1. Valida credenciales con Glovo API
     * 2. Guarda .pem en filesystem
     * 3. Inserta credenciales en glovo_credentials
     * 4. Crea schema PostgreSQL con todas las tablas
     * 5. Inserta configuraciones en tenant_settings
     * 6. Activa el tenant
     *
     * @param pemFile Archivo .pem con clave privada RSA
     * @param onboardingData Credenciales y configuraciones (JSON)
     * @return Status del onboarding completado o error
     */
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @PostMapping(value = "/configure", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> configureTenant(
            @RequestParam("pemFile") MultipartFile pemFile,
            @Valid @ModelAttribute OnboardingDto onboardingData
    ) {
        try {
            // Obtener tenantId del contexto (desde JWT + X-Tenant-Id header)
            TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();

            if (tenantInfo == null || tenantInfo.getFirstTenantId() == null) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Tenant no encontrado");
                error.put("message", "No se pudo determinar el tenant desde el JWT y header X-Tenant-Id.");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
            }

            Long tenantId = tenantInfo.getFirstTenantId();

            logger.info("Tenant {}: Iniciando configuración de onboarding", tenantId);

            // Validar que el archivo .pem esté presente
            if (pemFile == null || pemFile.isEmpty()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Archivo .pem requerido");
                error.put("message", "Debe proporcionar un archivo .pem con la clave privada");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }

            // Provisionar tenant (proceso completo automático)
            OnboardingStatusDto result = onboardingService.provisionTenant(
                    tenantId,
                    onboardingData,
                    pemFile
            );

            logger.info("Tenant {}: Onboarding completado exitosamente", tenantId);
            return ResponseEntity.ok(result);

        } catch (IllegalStateException e) {
            // Tenant ya configurado
            logger.warn("Tenant ya configurado: {}", e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", "Tenant ya configurado");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error);

        } catch (IllegalArgumentException e) {
            // Datos inválidos (credenciales, .pem, etc.)
            logger.error("Datos inválidos en onboarding: {}", e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", "Datos inválidos");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);

        } catch (Exception e) {
            // Error durante provisioning (credenciales inválidas, error BD, etc.)
            logger.error("Error durante onboarding: {}", e.getMessage(), e);
            Map<String, String> error = new HashMap<>();
            error.put("error", "Error en el provisioning");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Actualiza configuraciones de un tenant existente.
     * Permite actualizar credenciales, ciudades activas, y configuraciones.
     *
     * @param pemFile Archivo .pem (opcional, solo si se cambian credenciales)
     * @param updateData Nuevas configuraciones
     * @return Status actualizado
     */
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @PutMapping(value = "/update", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> updateTenantConfiguration(
            @RequestParam(value = "pemFile", required = false) MultipartFile pemFile,
            @Valid @ModelAttribute OnboardingDto updateData
    ) {
        try {
            // Obtener tenantId del contexto (desde JWT + X-Tenant-Id header)
            TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();

            if (tenantInfo == null || tenantInfo.getFirstTenantId() == null) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Tenant no encontrado");
                error.put("message", "No se pudo determinar el tenant desde el JWT y header X-Tenant-Id.");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
            }

            Long tenantId = tenantInfo.getFirstTenantId();

            logger.info("Tenant {}: Actualizando configuración", tenantId);

            // Actualizar configuración
            OnboardingStatusDto result = onboardingService.updateTenantConfiguration(
                    tenantId,
                    updateData,
                    pemFile
            );

            logger.info("Tenant {}: Configuración actualizada exitosamente", tenantId);
            return ResponseEntity.ok(result);

        } catch (IllegalStateException e) {
            // Tenant no configurado
            logger.warn("Tenant no configurado: {}", e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", "Tenant no configurado");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);

        } catch (IllegalArgumentException e) {
            // Datos inválidos
            logger.error("Datos inválidos: {}", e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", "Datos inválidos");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);

        } catch (Exception e) {
            logger.error("Error actualizando configuración: {}", e.getMessage(), e);
            Map<String, String> error = new HashMap<>();
            error.put("error", "Error en la actualización");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Endpoint de prueba para verificar que las credenciales Glovo son válidas.
     * No persiste nada, solo valida y retorna las ciudades disponibles.
     *
     * @param pemFile Archivo .pem
     * @param onboardingData Credenciales
     * @return Success con lista de ciudades si las credenciales son válidas
     */
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @PostMapping(value = "/validate-credentials", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> validateCredentials(
            @RequestParam("pemFile") MultipartFile pemFile,
            @Valid @ModelAttribute OnboardingDto onboardingData
    ) {
        try {
            logger.info("Validando credenciales Glovo y obteniendo ciudades...");

            // Validar credenciales y obtener ciudades disponibles
            Map<String, Object> result = onboardingService.validateCredentialsAndGetCities(onboardingData, pemFile);

            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            logger.error("Credenciales inválidas: {}", e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", "Credenciales inválidas");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);

        } catch (Exception e) {
            logger.error("Error validando credenciales: {}", e.getMessage(), e);
            Map<String, String> error = new HashMap<>();
            error.put("error", "Error en la validación");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}
