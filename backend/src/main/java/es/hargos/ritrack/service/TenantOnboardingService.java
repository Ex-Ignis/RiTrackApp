package es.hargos.ritrack.service;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import es.hargos.ritrack.client.GlovoClient;
import es.hargos.ritrack.dto.OnboardingDto;
import es.hargos.ritrack.dto.OnboardingStatusDto;
import es.hargos.ritrack.entity.GlovoCredentialsEntity;
import es.hargos.ritrack.entity.TenantEntity;
import es.hargos.ritrack.entity.TenantSettingsEntity;
import es.hargos.ritrack.repository.GlovoCredentialsRepository;
import es.hargos.ritrack.repository.TenantRepository;
import es.hargos.ritrack.repository.TenantSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Servicio para gestionar el onboarding automático de nuevos tenants.
 *
 * Flujo de onboarding:
 * 1. Usuario compra RiTrack en HargosAuth → se crea entrada básica en public.tenants
 * 2. Usuario accede a RiTrack y verifica status con getOnboardingStatus()
 * 3. Si no está configurado, frontend muestra formulario de credenciales
 * 4. Usuario envía credenciales + .pem file
 * 5. Este servicio:
 *    - Valida credenciales con Glovo API (llamada de prueba)
 *    - Guarda .pem en filesystem
 *    - Inserta credenciales en glovo_credentials
 *    - Ejecuta create_tenant_schema() para crear todas las tablas
 *    - Inserta configuraciones en tenant_settings
 *    - Activa el tenant
 * 6. Usuario ya puede usar RiTrack completamente configurado
 */
@Service
public class TenantOnboardingService {

    private static final Logger logger = LoggerFactory.getLogger(TenantOnboardingService.class);

    private final TenantRepository tenantRepository;
    private final GlovoCredentialsRepository credentialsRepository;
    private final TenantSettingsRepository settingsRepository;
    private final FileStorageService fileStorageService;
    private final TenantSchemaService schemaService;
    private final GlovoClient glovoClient;
    private final RiderLimitService riderLimitService;
    private final RestTemplate restTemplate;

    @Autowired
    public TenantOnboardingService(TenantRepository tenantRepository,
                                     GlovoCredentialsRepository credentialsRepository,
                                     TenantSettingsRepository settingsRepository,
                                     FileStorageService fileStorageService,
                                     TenantSchemaService schemaService,
                                     GlovoClient glovoClient,
                                     RiderLimitService riderLimitService) {
        this.tenantRepository = tenantRepository;
        this.credentialsRepository = credentialsRepository;
        this.settingsRepository = settingsRepository;
        this.fileStorageService = fileStorageService;
        this.schemaService = schemaService;
        this.glovoClient = glovoClient;
        this.riderLimitService = riderLimitService;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Verifica el estado del onboarding de un tenant.
     *
     * @param tenantId ID del tenant
     * @return Status del onboarding indicando si está configurado o necesita setup
     */
    public OnboardingStatusDto getOnboardingStatus(Long tenantId) {
        logger.debug("Verificando status de onboarding para tenant {}", tenantId);

        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant no encontrado: " + tenantId));

        // Verificar si tiene credenciales Glovo activas
        boolean hasCredentials = credentialsRepository.findByTenantIdAndIsActive(tenantId, true).isPresent();

        // Verificar si el schema existe con su estructura completa
        boolean schemaValid = schemaService.schemaExists(tenant.getSchemaName()) &&
                schemaService.validateSchemaStructure(tenant.getSchemaName());

        boolean configured = hasCredentials && schemaValid && tenant.getIsActive();

        if (configured) {
            return OnboardingStatusDto.configured(tenant.getName(), tenant.getSchemaName());
        } else {
            return OnboardingStatusDto.needsSetup(tenant.getName(), tenant.getSchemaName());
        }
    }

    /**
     * Verifica si un tenant está completamente configurado.
     *
     * @param tenantId ID del tenant
     * @return true si el tenant tiene credenciales y schema configurado
     */
    public boolean isTenantConfigured(Long tenantId) {
        OnboardingStatusDto status = getOnboardingStatus(tenantId);
        return status.isConfigured();
    }

    /**
     * Verifica si un tenant está listo para operaciones (tiene credenciales activas Y validadas).
     * Más rápido que isTenantConfigured() porque no valida la estructura del schema.
     *
     * @param tenantId ID del tenant
     * @return true si el tenant tiene credenciales activas y validadas
     */
    public boolean isTenantReady(Long tenantId) {
        try {
            return credentialsRepository.findByTenantIdAndIsActiveAndIsValidated(tenantId, true, true).isPresent();
        } catch (Exception e) {
            logger.error("Error verificando si tenant {} está listo: {}", tenantId, e.getMessage());
            return false;
        }
    }

    /**
     * Provisiona un tenant completo de forma automática.
     *
     * Pasos:
     * 1. Valida credenciales Glovo con llamada de prueba
     * 2. Guarda .pem file en filesystem
     * 3. Inserta credenciales en glovo_credentials
     * 4. Crea schema PostgreSQL con todas las tablas
     * 5. Inserta configuraciones en tenant_settings
     * 6. Activa el tenant
     *
     * @param tenantId ID del tenant a provisionar
     * @param onboardingData Datos de configuración (credenciales + settings)
     * @param pemFile Archivo .pem con la clave privada
     * @return Status del onboarding completado
     * @throws Exception Si falla algún paso del provisioning
     */
    @Transactional
    public OnboardingStatusDto provisionTenant(Long tenantId, OnboardingDto onboardingData, MultipartFile pemFile) throws Exception {
        logger.info("Iniciando provisioning para tenant {}", tenantId);

        // 1. Obtener tenant
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant no encontrado: " + tenantId));

        // 2. Verificar que no esté ya configurado
        if (isTenantConfigured(tenantId)) {
            throw new IllegalStateException("Tenant ya está configurado. Use endpoints de actualización.");
        }

        try {
            // 3. Guardar archivo .pem temporalmente para validación
            String pemPath = fileStorageService.savePemFile(tenantId, pemFile);
            logger.info("Tenant {}: Archivo .pem guardado en {}", tenantId, pemPath);

            // 4. Validar credenciales con Glovo API (llamada de prueba)
            logger.info("Tenant {}: Validando credenciales con Glovo API...", tenantId);
            validateGlovoCredentials(onboardingData, pemPath);
            logger.info("Tenant {}: Credenciales Glovo validadas correctamente", tenantId);

            // 4.5. Auto-detectar companyId y contractId si no se proporcionaron
            if (onboardingData.getCompanyId() == null || onboardingData.getContractId() == null) {
                logger.info("Tenant {}: Auto-detectando companyId y/o contractId...", tenantId);
                autoDetectCompanyAndContract(onboardingData, pemPath);
            }

            // 5. Insertar credenciales en BD
            GlovoCredentialsEntity credentials = createGlovoCredentials(tenant, onboardingData, pemPath);
            credentialsRepository.save(credentials);
            logger.info("Tenant {}: Credenciales guardadas en BD", tenantId);

            // 6. Crear schema PostgreSQL con todas las tablas
            logger.info("Tenant {}: Creando schema '{}'...", tenantId, tenant.getSchemaName());
            schemaService.createSchema(tenant.getSchemaName());
            logger.info("Tenant {}: Schema creado exitosamente", tenantId);

            // 7. Insertar configuraciones en tenant_settings
            insertTenantSettings(tenant, onboardingData);
            logger.info("Tenant {}: Configuraciones guardadas", tenantId);

            // 8. Activar tenant
            tenant.setIsActive(true);
            tenantRepository.save(tenant);
            logger.info("Tenant {}: Activado exitosamente", tenantId);

            // 9. Validar límites de riders
            try {
                logger.info("Tenant {}: Validando límites de riders...", tenantId);

                Long hargosTenantId = tenant.getHargosTenantId();

                if (hargosTenantId == null) {
                    logger.warn("Tenant {}: No tiene hargosTenantId, saltando validación de límites", tenantId);
                } else {
                    var warning = riderLimitService.validateDuringOnboarding(tenantId, hargosTenantId);

                    if (warning != null) {
                        logger.warn("Tenant {}: ADVERTENCIA DE LÍMITE CREADA - Riders actuales: {}, Límite: {}, Exceso: {}, Expira: {}",
                                tenantId, warning.getCurrentCount(), warning.getAllowedLimit(),
                                warning.getExcessCount(), warning.getExpiresAt());
                    } else {
                        logger.info("Tenant {}: Sin excesos de límite detectados", tenantId);
                    }
                }
            } catch (Exception e) {
                // FAIL-SAFE: Si falla la validación, NO bloquear onboarding
                // pero registrar error para investigación
                logger.error("Tenant {}: Error validando límites (onboarding continuará): {}",
                        tenantId, e.getMessage(), e);
            }

            logger.info("Tenant {}: Provisioning completado exitosamente", tenantId);

            return OnboardingStatusDto.configured(tenant.getName(), tenant.getSchemaName());

        } catch (Exception e) {
            logger.error("Tenant {}: Error durante provisioning: {}", tenantId, e.getMessage(), e);

            // Rollback: el @Transactional se encargará de revertir los cambios en BD
            // pero necesitamos limpiar el archivo .pem si se guardó
            try {
                String pemPath = "./keys/tenant_" + tenantId + ".pem";
                fileStorageService.deletePemFile(pemPath);
            } catch (Exception cleanupError) {
                logger.warn("Tenant {}: Error limpiando .pem después de fallo: {}",
                           tenantId, cleanupError.getMessage());
            }

            throw new Exception("Error durante el provisioning del tenant: " + e.getMessage(), e);
        }
    }

    /**
     * Actualiza configuración de un tenant existente.
     * Permite actualizar credenciales, ciudades activas, y otras configuraciones.
     *
     * @param tenantId ID del tenant a actualizar
     * @param updateData Nuevas configuraciones
     * @param pemFile Nuevo archivo .pem (opcional, solo si se cambian credenciales)
     * @return Status del tenant actualizado
     * @throws Exception Si falla la actualización
     */
    @Transactional
    public OnboardingStatusDto updateTenantConfiguration(Long tenantId, OnboardingDto updateData, MultipartFile pemFile) throws Exception {
        logger.info("Tenant {}: Iniciando actualización de configuración", tenantId);

        // 1. Obtener tenant
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant no encontrado: " + tenantId));

        // 2. Verificar que el tenant YA está configurado
        if (!isTenantConfigured(tenantId)) {
            throw new IllegalStateException("Tenant no está configurado. Use POST /configure primero.");
        }

        try {
            // 3. Obtener credenciales existentes
            GlovoCredentialsEntity existingCredentials = credentialsRepository.findByTenantId(tenantId)
                    .orElseThrow(() -> new IllegalStateException("No se encontraron credenciales para el tenant"));

            boolean credentialsChanged = false;

            // 4. Si se proporcionan nuevas credenciales, validarlas
            if (updateData.getClientId() != null || updateData.getKeyId() != null || pemFile != null) {
                logger.info("Tenant {}: Actualizando credenciales de Glovo", tenantId);

                String pemPath;
                if (pemFile != null && !pemFile.isEmpty()) {
                    // Guardar nuevo .pem (reemplaza el antiguo)
                    pemPath = fileStorageService.savePemFile(tenantId, pemFile);
                    logger.info("Tenant {}: Nuevo archivo .pem guardado en {}", tenantId, pemPath);
                } else {
                    // Mantener el .pem existente
                    pemPath = existingCredentials.getPrivateKeyPath();
                }

                // Usar valores nuevos o mantener existentes
                String clientId = updateData.getClientId() != null ? updateData.getClientId() : existingCredentials.getClientId();
                String keyId = updateData.getKeyId() != null ? updateData.getKeyId() : existingCredentials.getKeyId();

                // Validar nuevas credenciales
                OnboardingDto validationData = new OnboardingDto();
                validationData.setClientId(clientId);
                validationData.setKeyId(keyId);
                validationData.setCompanyId(updateData.getCompanyId());
                validationData.setContractId(updateData.getContractId());

                logger.info("Tenant {}: Validando nuevas credenciales...", tenantId);
                validateGlovoCredentials(validationData, pemPath);

                // Auto-detectar companyId/contractId si no se proporcionaron
                if (validationData.getCompanyId() == null || validationData.getContractId() == null) {
                    logger.info("Tenant {}: Auto-detectando companyId/contractId...", tenantId);
                    autoDetectCompanyAndContract(validationData, pemPath);
                }

                // Actualizar credenciales
                existingCredentials.setClientId(clientId);
                existingCredentials.setKeyId(keyId);
                existingCredentials.setPrivateKeyPath(pemPath);
                existingCredentials.setCompanyId(validationData.getCompanyId());
                existingCredentials.setContractId(validationData.getContractId());
                existingCredentials.setIsValidated(true);
                existingCredentials.setLastValidatedAt(LocalDateTime.now());

                credentialsRepository.save(existingCredentials);
                logger.info("Tenant {}: Credenciales actualizadas en BD", tenantId);

                credentialsChanged = true;
            }

            // 5. Actualizar settings si se proporcionan
            if (updateData.getActiveCityIds() != null && !updateData.getActiveCityIds().isEmpty()) {
                String cityIds = updateData.getActiveCityIds().stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(","));

                updateOrCreateSetting(tenant, "active_city_ids", cityIds, "JSON", "Ciudades activas para monitoreo");
                logger.info("Tenant {}: Ciudades activas actualizadas", tenantId);
            }

            if (updateData.getEmailDomain() != null) {
                updateOrCreateSetting(tenant, "rider_email_domain", updateData.getEmailDomain(), "STRING", "Dominio para emails");
            }

            if (updateData.getEmailBase() != null) {
                updateOrCreateSetting(tenant, "rider_email_base", updateData.getEmailBase(), "STRING", "Base de email");
            }

            if (updateData.getNameBase() != null) {
                updateOrCreateSetting(tenant, "rider_name_base", updateData.getNameBase(), "STRING", "Prefijo para nombres");
            }

            if (updateData.getDefaultVehicleTypeIds() != null && !updateData.getDefaultVehicleTypeIds().isEmpty()) {
                String vehicleIds = updateData.getDefaultVehicleTypeIds().stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(","));

                updateOrCreateSetting(tenant, "default_vehicle_type_ids", vehicleIds, "JSON", "Tipos de vehículo por defecto");
            }

            // 6. Si cambiaron credenciales, invalidar token cache
            if (credentialsChanged) {
                logger.info("Tenant {}: Invalidando cache de tokens...", tenantId);
                // El token service se encargará de generar nuevo token en la próxima llamada
            }

            logger.info("Tenant {}: Configuración actualizada exitosamente", tenantId);

            return OnboardingStatusDto.configured(tenant.getName(), tenant.getSchemaName());

        } catch (Exception e) {
            logger.error("Tenant {}: Error durante actualización: {}", tenantId, e.getMessage(), e);
            throw new Exception("Error actualizando configuración del tenant: " + e.getMessage(), e);
        }
    }

    /**
     * Helper para actualizar o crear un setting.
     */
    private void updateOrCreateSetting(TenantEntity tenant, String key, String value, String type, String description) {
        Optional<TenantSettingsEntity> existing = settingsRepository
                .findByTenantIdAndSettingKey(tenant.getId(), key);

        if (existing.isPresent()) {
            TenantSettingsEntity setting = existing.get();
            setting.setSettingValue(value);
            setting.setUpdatedAt(LocalDateTime.now());
            settingsRepository.save(setting);
            logger.debug("Setting actualizado: {} = {}", key, value);
        } else {
            TenantSettingsEntity setting = TenantSettingsEntity.builder()
                    .tenant(tenant)
                    .settingKey(key)
                    .settingValue(value)
                    .settingType(type)
                    .description(description)
                    .build();
            settingsRepository.save(setting);
            logger.debug("Setting creado: {} = {}", key, value);
        }
    }

    /**
     * Valida credenciales y obtiene la lista de ciudades disponibles de Glovo API.
     * Este método NO persiste nada, solo valida y retorna información.
     *
     * @param onboardingData Credenciales a validar
     * @param pemFile Archivo .pem temporal
     * @return Map con valid=true y lista de ciudades
     * @throws Exception Si las credenciales no son válidas
     */
    public Map<String, Object> validateCredentialsAndGetCities(OnboardingDto onboardingData, MultipartFile pemFile) throws Exception {
        logger.info("Validando credenciales Glovo y obteniendo ciudades disponibles...");

        // Guardar .pem temporalmente
        String tempPemPath = null;
        try {
            // Guardar temporalmente (se eliminará al final)
            tempPemPath = fileStorageService.savePemFile(-1L, pemFile); // -1 indica temporal

            // 1. Generar JWT client assertion
            String clientAssertion = generateJwtClientAssertion(
                    onboardingData.getClientId(),
                    onboardingData.getKeyId(),
                    getAudienceUrl(onboardingData),
                    tempPemPath
            );

            // 2. Obtener access token OAuth2
            String accessToken = requestAccessToken(
                    clientAssertion,
                    getTokenUrl(onboardingData)
            );// 3. Obtener lista de ciudades desde Glovo API
            List<Map<String, Object>> cities = getCitiesFromGlovoApi(accessToken, getRoosterBaseUrl(onboardingData));
            // 4. Obtener lista de contratos para extraer company_id y contract_id
            List<Map<String, Object>> contracts = getContractsFromGlovoApi(accessToken, getRoosterBaseUrl(onboardingData));
            // 5. Extraer company_id del primer contrato (todos tienen el mismo company_id)
            Integer companyId = null;
            Integer suggestedContractId = null;
            if (!contracts.isEmpty()) {
                Map<String, Object> firstContract = contracts.get(0);
                companyId = (Integer) firstContract.get("company_id");
                logger.info("llega a la linea 424");
                // Si hay un solo contrato activo, sugerirlo
                if (contracts.size() == 1) {
                    suggestedContractId = (Integer) firstContract.get("id");
                    logger.info("Auto-detectado: companyId={}, contractId={}", companyId, suggestedContractId);
                } else {
                    logger.info("Auto-detectado companyId={}. {} contratos disponibles para selección.", companyId, contracts.size());
                }
            }

            logger.info("Credenciales validadas. Se encontraron {} ciudades y {} contratos", cities.size(), contracts.size());

            Map<String, Object> response = new HashMap<>();
            response.put("valid", true);
            response.put("message", "Credenciales válidas");
            response.put("cities", cities);
            response.put("totalCities", cities.size());
            response.put("contracts", contracts);
            response.put("totalContracts", contracts.size());
            response.put("companyId", companyId);
            response.put("suggestedContractId", suggestedContractId);

            return response;

        } catch (Exception e) {
            logger.error("Error validando credenciales: {}", e.getMessage());
            throw new IllegalArgumentException("Credenciales Glovo inválidas: " + e.getMessage(), e);

        } finally {
            // Limpiar archivo temporal
            if (tempPemPath != null) {
                try {
                    fileStorageService.deletePemFile(tempPemPath);
                    logger.debug("Archivo .pem temporal eliminado");
                } catch (Exception e) {
                    logger.warn("Error eliminando .pem temporal: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Auto-detecta companyId y contractId desde la API de Glovo.
     * Modifica el OnboardingDto con los valores detectados.
     *
     * @param onboardingData DTO que se modificará con los valores detectados
     * @param pemPath Ruta al archivo .pem
     * @throws Exception Si no se pueden detectar los valores
     */
    private void autoDetectCompanyAndContract(OnboardingDto onboardingData, String pemPath) throws Exception {
        try {
            // 1. Generar JWT client assertion
            String clientAssertion = generateJwtClientAssertion(
                    onboardingData.getClientId(),
                    onboardingData.getKeyId(),
                    getAudienceUrl(onboardingData),
                    pemPath
            );

            // 2. Obtener access token OAuth2
            String accessToken = requestAccessToken(
                    clientAssertion,
                    getTokenUrl(onboardingData)
            );

            // 3. Obtener contratos desde API
            List<Map<String, Object>> contracts = getContractsFromGlovoApi(accessToken, getRoosterBaseUrl(onboardingData));

            if (contracts.isEmpty()) {
                throw new IllegalArgumentException("No se encontraron contratos disponibles para este usuario");
            }

            // 4. Extraer companyId del primer contrato (todos tienen el mismo)
            Map<String, Object> firstContract = contracts.get(0);
            Integer companyId = (Integer) firstContract.get("company_id");

            if (companyId == null) {
                throw new IllegalArgumentException("No se pudo detectar el company_id desde la API de Glovo");
            }

            // 5. Auto-asignar companyId si no fue proporcionado
            if (onboardingData.getCompanyId() == null) {
                onboardingData.setCompanyId(companyId);
                logger.info("Auto-detectado companyId: {}", companyId);
            }

            // 6. Auto-asignar contractId si no fue proporcionado y solo hay un contrato
            if (onboardingData.getContractId() == null) {
                if (contracts.size() == 1) {
                    Integer contractId = (Integer) firstContract.get("id");
                    onboardingData.setContractId(contractId);
                    logger.info("Auto-detectado contractId: {} (único contrato disponible)", contractId);
                } else {
                    throw new IllegalArgumentException(
                            "Se encontraron " + contracts.size() + " contratos. Debe especificar contractId. " +
                            "Use el endpoint /validate-credentials para ver los contratos disponibles."
                    );
                }
            }

        } catch (Exception e) {
            logger.error("Error auto-detectando company/contract: {}", e.getMessage());
            throw new Exception("No se pudo auto-detectar companyId/contractId: " + e.getMessage(), e);
        }
    }

    /**
     * Valida credenciales Glovo haciendo una llamada de prueba a la API.
     * Intenta obtener la lista de ciudades como verificación.
     *
     * @param onboardingData Credenciales a validar
     * @param pemPath Ruta al archivo .pem
     * @throws Exception Si las credenciales no son válidas
     */
    private void validateGlovoCredentials(OnboardingDto onboardingData, String pemPath) throws Exception {
        logger.debug("Validando credenciales Glovo...");

        try {
            // 1. Generar JWT client assertion
            String clientAssertion = generateJwtClientAssertion(
                    onboardingData.getClientId(),
                    onboardingData.getKeyId(),
                    getAudienceUrl(onboardingData),
                    pemPath
            );

            // 2. Obtener access token OAuth2
            String accessToken = requestAccessToken(
                    clientAssertion,
                    getTokenUrl(onboardingData)
            );

            logger.info("✅✅✅ ACCESS TOKEN OBTENIDO, PROCEDIENDO A VALIDAR ✅✅✅");

            // 3. Hacer llamada de prueba a Glovo API (GET /cities) para verificar que el token funciona
            String citiesUrl = getRoosterBaseUrl(onboardingData) + "/v3/external/cities";
            logger.info("=== VALIDATING GLOVO CREDENTIALS (INLINE) ===");
            logger.info("URL: {}", citiesUrl);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            HttpEntity<Void> request = new HttpEntity<>(headers);

            try {
                ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    citiesUrl,
                    HttpMethod.GET,
                    request,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {}
                );

                List<Map<String, Object>> cities = response.getBody();
                logger.info("=== GLOVO API VALIDATION SUCCESS ===");
                logger.info("Credenciales Glovo validadas exitosamente. Ciudades disponibles: {}", cities != null ? cities.size() : 0);
            } catch (HttpClientErrorException | HttpServerErrorException e) {
                logger.error("=== GLOVO API VALIDATION ERROR ===");
                logger.error("Status: {}", e.getStatusCode());
                logger.error("Response Body: {}", e.getResponseBodyAsString());
                throw new Exception("Glovo API error " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
            }

        } catch (Exception e) {
            logger.error("❌❌❌ ERROR COMPLETO ❌❌❌", e);
            logger.error("Error validando credenciales Glovo: {}", e.getMessage());
            throw new Exception("Credenciales Glovo inválidas: " + e.getMessage(), e);
        }
    }

    /**
     * Genera un JWT client assertion para OAuth2.
     */
    private String generateJwtClientAssertion(String clientId, String keyId, String audience, String pemPath) throws Exception {
        // Leer clave privada del .pem
        String pemContent = Files.readString(Path.of(pemPath));
        RSAPrivateKey privateKey = parsePrivateKey(pemContent);

        // Crear JWT header
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(keyId)
                .build();

        // Crear JWT claims
        Instant now = Instant.now();
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .issuer(clientId)
                .subject(clientId)
                .audience(audience)
                .expirationTime(java.util.Date.from(now.plusSeconds(60))) // 60 segundos
                .jwtID(UUID.randomUUID().toString())
                .build();

        // Firmar JWT
        SignedJWT signedJWT = new SignedJWT(header, claimsSet);
        JWSSigner signer = new RSASSASigner(privateKey);
        signedJWT.sign(signer);

        return signedJWT.serialize();
    }

    /**
     * Parsea una clave privada RSA desde contenido PEM.
     */
    private RSAPrivateKey parsePrivateKey(String pemContent) throws Exception {
        String privateKeyPEM = pemContent
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        byte[] encoded = Base64.getDecoder().decode(privateKeyPEM);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return (RSAPrivateKey) keyFactory.generatePrivate(keySpec);
    }

    /**
     * Solicita access token OAuth2 usando client assertion.
     */
    private String requestAccessToken(String clientAssertion, String tokenUrl) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        String body = String.format(
                "grant_type=client_credentials&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=%s",
                clientAssertion
        );

        logger.info("Generated JWT client assertion: {}", clientAssertion);
        logger.info("Token URL: {}", tokenUrl);
        logger.info("Request body: {}", body);

        HttpEntity<String> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    tokenUrl,
                    HttpMethod.POST,
                    request,
                    Map.class
            );

            logger.info("OAuth2 response status: {}", response.getStatusCode());
            logger.info("OAuth2 response body: {}", response.getBody());

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                throw new Exception("Failed to get access token");
            }

            return (String) response.getBody().get("access_token");
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            logger.error("HTTP error from Glovo OAuth2 endpoint - Status: {}, Response: {}",
                      e.getStatusCode(), e.getResponseBodyAsString());
            throw new Exception("Glovo OAuth2 error: " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
        } catch (Exception e) {
            logger.error("Unexpected error requesting access token", e);
            throw e;
        }
    }

    /**
     * Obtiene la lista de contratos disponibles desde Glovo API.
     *
     * @param accessToken Token de acceso OAuth2
     * @param roosterBaseUrl Base URL de Glovo Rooster API
     * @return Lista de contratos con id, name, company_id
     * @throws Exception Si falla la llamada a la API
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getContractsFromGlovoApi(String accessToken, String roosterBaseUrl) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        HttpEntity<Void> request = new HttpEntity<>(headers);

        String contractsUrl = roosterBaseUrl + "/v3/external/contracts";

        ResponseEntity<List> response = restTemplate.exchange(
                contractsUrl,
                HttpMethod.GET,
                request,
                List.class
        );

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new Exception("Failed to get contracts from Glovo API: " + response.getStatusCode());
        }

        List<Map<String, Object>> contracts = (List<Map<String, Object>>) response.getBody();

        if (contracts == null) {
            throw new Exception("No contracts data in API response");
        }

        logger.debug("Obtenidos {} contratos desde Glovo API", contracts.size());

        // Simplificar estructura de contratos para el frontend
        return contracts.stream()
                .map(contract -> {
                    Map<String, Object> simplified = new HashMap<>();
                    simplified.put("id", contract.get("id"));
                    simplified.put("name", contract.get("name"));
                    simplified.put("type", contract.get("type"));
                    simplified.put("company_id", contract.get("company_id"));
                    return simplified;
                })
                .collect(Collectors.toList());
    }

    /**
     * Obtiene la lista completa de ciudades disponibles desde Glovo API.
     *
     * @param accessToken Token de acceso OAuth2
     * @param roosterBaseUrl Base URL de Glovo Rooster API
     * @return Lista de ciudades con id, name, country
     * @throws Exception Si falla la llamada a la API
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getCitiesFromGlovoApi(String accessToken, String roosterBaseUrl) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        HttpEntity<Void> request = new HttpEntity<>(headers);

        String citiesUrl = roosterBaseUrl + "/v3/external/cities";

        ResponseEntity<List> response = restTemplate.exchange(
                citiesUrl,
                HttpMethod.GET,
                request,
                List.class
        );

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new Exception("Failed to get cities from Glovo API: " + response.getStatusCode());
        }

        List<Map<String, Object>> cities = (List<Map<String, Object>>) response.getBody();

        if (cities == null) {
            throw new Exception("No cities data in API response");
        }

        logger.debug("Obtenidas {} ciudades desde Glovo API", cities.size());

        // Simplificar estructura de ciudades para el frontend
        return cities.stream()
                .map(city -> {
                    Map<String, Object> simplified = new HashMap<>();
                    simplified.put("id", city.get("id"));
                    simplified.put("name", city.get("name"));
                    simplified.put("time_zone", city.get("time_zone"));
                    return simplified;
                })
                .collect(Collectors.toList());
    }

    /**
     * Crea entidad GlovoCredentialsEntity desde OnboardingDto.
     */
    private GlovoCredentialsEntity createGlovoCredentials(TenantEntity tenant, OnboardingDto data, String pemPath) {
        return GlovoCredentialsEntity.builder()
                .tenant(tenant)
                .clientId(data.getClientId())
                .keyId(data.getKeyId())
                .privateKeyPath(pemPath)
                .audienceUrl(getAudienceUrl(data))
                .tokenUrl(getTokenUrl(data))
                .roosterBaseUrl(getRoosterBaseUrl(data))
                .liveBaseUrl(getLiveBaseUrl(data))
                .companyId(data.getCompanyId())
                .contractId(data.getContractId())
                .isActive(true)
                .isValidated(true) // Ya validamos arriba
                .build();
    }

    /**
     * Inserta configuraciones por defecto en tenant_settings.
     */
    private void insertTenantSettings(TenantEntity tenant, OnboardingDto data) {
        // active_city_ids
        if (data.getActiveCityIds() != null && !data.getActiveCityIds().isEmpty()) {
            String cityIds = data.getActiveCityIds().stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));

            saveSetting(tenant, "active_city_ids", cityIds, "JSON", "Ciudades activas para monitoreo de riders");
        }

        // rider_email_domain
        saveSetting(tenant, "rider_email_domain", data.getEmailDomain(), "STRING", "Dominio para emails automáticos de riders");

        // rider_email_base
        saveSetting(tenant, "rider_email_base", data.getEmailBase(), "STRING", "Base de email para riders");

        // rider_name_base
        saveSetting(tenant, "rider_name_base", data.getNameBase(), "STRING", "Prefijo para nombres automáticos de riders");

        // default_vehicle_type_ids
        if (data.getDefaultVehicleTypeIds() != null && !data.getDefaultVehicleTypeIds().isEmpty()) {
            String vehicleIds = data.getDefaultVehicleTypeIds().stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));

            saveSetting(tenant, "default_vehicle_type_ids", vehicleIds, "JSON", "Tipos de vehículo por defecto");
        } else {
            // Default: Bike, Car, Motorbike, Scooter
            saveSetting(tenant, "default_vehicle_type_ids", "5,1,3,2", "JSON", "Tipos de vehículo por defecto");
        }

        // auto_block_enabled (DESACTIVADO por defecto)
        saveSetting(tenant, "auto_block_enabled", "false", "BOOLEAN", "Activar bloqueo automático por saldo de cash alto");

        // auto_block_cash_limit (límite por defecto: 150€)
        saveSetting(tenant, "auto_block_cash_limit", "150.00", "NUMBER", "Límite de cash en € para bloqueo automático");
    }

    /**
     * Helper para guardar un setting.
     */
    private void saveSetting(TenantEntity tenant, String key, String value, String type, String description) {
        TenantSettingsEntity setting = TenantSettingsEntity.builder()
                .tenant(tenant)
                .settingKey(key)
                .settingValue(value)
                .settingType(type)
                .description(description)
                .build();

        settingsRepository.save(setting);
        logger.debug("Setting guardado: {} = {}", key, value);
    }

    // Helpers para obtener URLs con valores por defecto
    private String getAudienceUrl(OnboardingDto data) {
        return data.getAudienceUrl() != null && !data.getAudienceUrl().isEmpty()
                ? data.getAudienceUrl()
                : "https://sts.deliveryhero.io";
    }

    private String getTokenUrl(OnboardingDto data) {
        return data.getTokenUrl() != null && !data.getTokenUrl().isEmpty()
                ? data.getTokenUrl()
                : "https://sts.dh-auth.io/oauth2/token";
    }

    private String getRoosterBaseUrl(OnboardingDto data) {
        return data.getRoosterBaseUrl() != null && !data.getRoosterBaseUrl().isEmpty()
                ? data.getRoosterBaseUrl()
                : "https://gv-es.usehurrier.com/api/rooster";
    }

    private String getLiveBaseUrl(OnboardingDto data) {
        return data.getLiveBaseUrl() != null && !data.getLiveBaseUrl().isEmpty()
                ? data.getLiveBaseUrl()
                : "https://gv-es.usehurrier.com/api/rider-live-operations";
    }
}
