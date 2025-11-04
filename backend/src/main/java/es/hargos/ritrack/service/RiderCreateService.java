package es.hargos.ritrack.service;

import es.hargos.ritrack.client.GlovoClient;
import es.hargos.ritrack.dto.RiderCreateDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RiderCreateService {

    private static final Logger logger = LoggerFactory.getLogger(RiderCreateService.class);

    @Value("${contract.id:344}")
    private Integer defaultContractId;

    @Value("#{${rider.default.starting-points:T(java.util.Collections).emptyMap()}}")
    private Map<String, List<Integer>> defaultStartingPointsByCity;

    private final GlovoClient glovoClient;
    private final RoosterCacheService roosterCache;
    private final TenantSettingsService tenantSettingsService;

    // Contador en memoria para emails (se reinicia con cada restart)
    private final AtomicInteger emailCounter = new AtomicInteger(1000);

    // Patrón para extraer número del email
    private final Pattern EMAIL_PATTERN = Pattern.compile("arendeltech\\+(\\d+)@");

    @Autowired
    public RiderCreateService(GlovoClient glovoClient,
                              RoosterCacheService roosterCache,
                              TenantSettingsService tenantSettingsService) {
        this.glovoClient = glovoClient;
        this.roosterCache = roosterCache;
        this.tenantSettingsService = tenantSettingsService;
    }


    /**
     * Genera el siguiente email disponible (coge total en cache suma 10000 + 1)
     * arendeltech+0010523@gmail.com
     */
    private String generateNextEmail(Long tenantId) throws Exception {
        List<?> employees = roosterCache.getAllEmployees(tenantId);
        int totalEmployees = employees != null ? employees.size() : 0;
        int emailNumber = totalEmployees + 10000;

        String emailDomain = tenantSettingsService.getEmailDomain(tenantId);
        String emailBase = tenantSettingsService.getEmailBase(tenantId);

        String email = String.format("%s+%d@%s", emailDomain, emailNumber, emailBase);
        logger.info("Tenant {}: Email generado basado en {} empleados: {}", tenantId, totalEmployees, email);
        return email;
    }

    /**
     * Genera un nombre automático si no se proporciona
     */
    private String generateNextName(Long tenantId) throws Exception {
        List<?> employees = roosterCache.getAllEmployees(tenantId);
        int totalEmployees = 0;

        if (employees != null && !employees.isEmpty()) {
            totalEmployees = employees.size();
        }

        String nameBase = tenantSettingsService.getNameBase(tenantId);
        int nameNumber = totalEmployees + 10000;
        String name = String.format("%s %d", nameBase, nameNumber);

        logger.info("Tenant {}: Nombre generado automáticamente: {}", tenantId, name);
        return name;
    }

    private void validateUniqueConstraints(Long tenantId, String email, String phone) throws Exception {
        List<?> employees = roosterCache.getAllEmployees(tenantId);
        if (employees == null) return;

        for (Object emp : employees) {
            if (emp instanceof Map) {
                Map<?, ?> empMap = (Map<?, ?>) emp;
                String existingEmail = (String) empMap.get("email");
                String existingPhone = (String) empMap.get("phone_number");

                if (existingEmail != null && existingEmail.equalsIgnoreCase(email)) {
                    throw new IllegalArgumentException("Tenant " + tenantId + ": El email " + email + " ya está en uso por otro rider");
                }
                if (existingPhone != null && existingPhone.equals(phone)) {
                    throw new IllegalArgumentException("Tenant " + tenantId + ": El teléfono " + phone + " ya está en uso por otro rider");
                }
            }
        }
    }

    public Map<String, Object> createRider(Long tenantId, RiderCreateDto createData) throws Exception {
        logger.info("Tenant {}: Iniciando creación de rider", tenantId);

        // Si no viene nombre o está vacío, generarlo automáticamente
        if (createData.getName() == null || createData.getName().trim().isEmpty()) {
            String generatedName = generateNextName(tenantId);
            createData.setName(generatedName);
            logger.info("Tenant {}: Nombre generado automáticamente: {}", tenantId, generatedName);
        }

        logger.info("Tenant {}: Creando nuevo rider: {}", tenantId, createData.getName());

        // Si no viene email, generarlo automáticamente
        if (createData.getEmail() == null || createData.getEmail().isEmpty()) {
            String generatedEmail = generateNextEmail(tenantId);
            createData.setEmail(generatedEmail);
            logger.info("Tenant {}: Email generado automáticamente: {}", tenantId, generatedEmail);
        }
        // Validar que operational_phone_number esté presente en fields
//        if (createData.getFields() == null ||
//                !createData.getFields().containsKey("operational_phone_number") ||
//                createData.getFields().get("operational_phone_number") == null ||
//                createData.getFields().get("operational_phone_number").trim().isEmpty()) {
//            throw new IllegalArgumentException("El campo operational_phone_number es obligatorio en fields");
//        }
//
//        // Validar formato del operational_phone_number
//        String operationalPhone = createData.getFields().get("operational_phone_number");
//        if (!operationalPhone.matches("^\\+?[0-9]{9,15}$")) {
//            throw new IllegalArgumentException("Formato de operational_phone_number inválido");
//        }
        // Modificar validación para que sea opcional:
        String operationalPhone = null;
        if (createData.getFields() != null &&
                createData.getFields().containsKey("operational_phone_number")) {
            operationalPhone = createData.getFields().get("operational_phone_number");

            // Validar formato solo si se proporciona
            if (operationalPhone != null && !operationalPhone.trim().isEmpty() &&
                    !operationalPhone.matches("^\\+?[0-9]{9,15}$")) {
                throw new IllegalArgumentException("Formato de operational_phone_number inválido");
            }
        }

        //Verificar duplicados antes de intentar crear
        validateUniqueConstraints(tenantId, createData.getEmail(), createData.getPhone());

        // Construir payload con valores por defecto
        Map<String, Object> payload = buildCreatePayload(tenantId, createData);

        // Log de valores asignados
        logAssignedValues(tenantId, payload);

        // Enviar a la API
        Object result = glovoClient.crearEmpleado(tenantId, payload);

        // Limpiar caché para reflejar cambios
        roosterCache.clearCache();
        logger.info("Tenant {}: Rider creado exitosamente y caché actualizado", tenantId);

        return (Map<String, Object>) result;
    }

    /**
     * Construye el payload con valores por defecto bien definidos
     */
    private Map<String, Object> buildCreatePayload(Long tenantId, RiderCreateDto data) {
        Map<String, Object> payload = new HashMap<>();

        // ===== CAMPOS REQUERIDOS =====
        payload.put("name", data.getName());
        payload.put("email", data.getEmail());
        payload.put("password", data.getPassword());

        if (data.getPhone() != null && !data.getPhone().trim().isEmpty()) {
            payload.put("phone_number", data.getPhone());
        }

        // ===== CAMPOS OPCIONALES CON VALORES POR DEFECTO =====

        // bank_data: null si no viene
        if (data.getBankData() != null && !data.getBankData().trim().isEmpty()) {
            payload.put("bank_data", data.getBankData());
        }

        // reporting_to: null si no viene
        if (data.getReportingTo() != null && data.getReportingTo() > 0) {
            payload.put("reporting_to", data.getReportingTo());
        }

        // city_id: null si no viene
        if (data.getCityId() != null && data.getCityId() > 0) {
            payload.put("city_id", data.getCityId());
        }

        // ===== CONTRATO (REQUERIDO) =====
        Map<String, Object> contractPayload = new HashMap<>();

        // contract_id: usar el proporcionado o el valor por defecto
        Integer contractId = data.getContract().getContractId();
        if (contractId == null || contractId <= 0) {
            contractId = defaultContractId;
            logger.info("Contract ID asignado por defecto: {}", contractId);
        }
        contractPayload.put("contract_id", contractId);

        // start_at: Si no viene o está vacío, usar fecha/hora actual
        String startAt = data.getContract().getStartAt();
        if (startAt == null || startAt.trim().isEmpty()) {
            startAt = LocalDateTime.now().minusHours(3).format(DateTimeFormatter.ISO_DATE_TIME) + "Z";
            logger.info("Fecha de inicio del contrato asignada automáticamente: {}", startAt);
        }
        contractPayload.put("start_at", formatDateTime(startAt));

        contractPayload.put("city_id", data.getContract().getCityId());

        // job_title: Por defecto "RIDER"
        String jobTitle = data.getContract().getJobTitle();
        if (jobTitle == null || jobTitle.trim().isEmpty()) {
            jobTitle = "RIDER";
        }
        contractPayload.put("job_title", jobTitle);

        // vehicle_type: null si no viene
        if (data.getContract().getVehicleType() != null &&
                !data.getContract().getVehicleType().trim().isEmpty()) {
            contractPayload.put("vehicle_type", data.getContract().getVehicleType());
        }

        // timezone: null si no viene
        if (data.getContract().getTimeZone() != null &&
                !data.getContract().getTimeZone().trim().isEmpty()) {
            contractPayload.put("with_time_zone", data.getContract().getTimeZone());
        }

        payload.put("contract", contractPayload);

        // ===== ARRAYS OPCIONALES =====

        // vehicle_type_ids: usar los proporcionados o los valores por defecto del tenant
        List<Integer> vehicleTypeIds = data.getVehicleTypeIds();
        if (vehicleTypeIds == null || vehicleTypeIds.isEmpty()) {
            vehicleTypeIds = new ArrayList<>(tenantSettingsService.getDefaultVehicleTypeIds(tenantId));
            logger.info("Vehicle type IDs asignados por defecto: {}", vehicleTypeIds);
        }
        payload.put("vehicle_type_ids", vehicleTypeIds);

        // starting_point_ids: usar los proporcionados o buscar por ciudad
        List<Integer> startingPointIds = data.getStartingPointIds();
        if (startingPointIds == null || startingPointIds.isEmpty()) {
            // Obtener la ciudad del contrato
            Integer cityId = data.getContract().getCityId();
            if (cityId != null) {
                // Buscar starting points por defecto para esta ciudad
                List<Integer> defaultPoints = defaultStartingPointsByCity.get(cityId.toString());
                if (defaultPoints != null && !defaultPoints.isEmpty()) {
                    startingPointIds = new ArrayList<>(defaultPoints);
                    logger.info("Starting points asignados automáticamente para ciudad {}: {}",
                            cityId, startingPointIds);
                } else {
                    logger.info("No hay starting points por defecto para ciudad {}", cityId);
                    startingPointIds = new ArrayList<>();
                }
            }
        }
        if (startingPointIds != null && !startingPointIds.isEmpty()) {
            payload.put("starting_point_ids", startingPointIds);
        }

        // ===== FIELDS PERSONALIZADOS =====
        List<Map<String, Object>> fieldsPayload = processFields(data.getFields(), data.getPhone());
        if (!fieldsPayload.isEmpty()) {
            payload.put("fields", fieldsPayload);
        }

        return payload;
    }

    /**
     * Procesa los fields con valores por defecto para algunos campos
     */
    private List<Map<String, Object>> processFields(Map<String, String> fields, String phoneNumber) {
        List<Map<String, Object>> fieldsPayload = new ArrayList<>();

        // Definir los campos disponibles y sus tipos
        Map<String, String> fieldTypes = Map.ofEntries(
                Map.entry("glovo_id", "string"),
                Map.entry("short_referral_url_id", "string"),
                Map.entry("address_postcode", "string"),
                Map.entry("material_received", "string"),
                Map.entry("operational_phone_number", "phone_number"),
                Map.entry("address_street", "string"),
                Map.entry("referral_url", "string"),
                Map.entry("mcc", "string"),
                Map.entry("tax_id_number", "string"),
                Map.entry("address_city", "string"),
                Map.entry("date_of_birth", "date"),
                Map.entry("short_referral_url", "string"),
                Map.entry("religion", "string"),
                Map.entry("birth_date", "date"),
                Map.entry("id_number", "string"),
                Map.entry("hasbox", "string"),
                Map.entry("irpf", "string"),
                Map.entry("iban", "string"),
                Map.entry("social_security_number", "string")
        );

        // Valores por defecto para algunos campos
        Map<String, String> defaultValues = Map.of(
                "material_received", "FALSE",
                "mcc", "FALSE",
                "hasbox", "FALSE",
                "religion", "0",
                "irpf", "0"
        );

        if (fields == null) {
            fields = new HashMap<>();
        }

        // NUEVO: Agregar operational_phone_number con el mismo teléfono si no viene
        if (!fields.containsKey("operational_phone_number") && phoneNumber != null) {
            fields.put("operational_phone_number", phoneNumber);
            logger.info("operational_phone_number asignado automáticamente: {}", phoneNumber);
        }

        // Aplicar valores por defecto si no vienen
        for (Map.Entry<String, String> defaultEntry : defaultValues.entrySet()) {
            if (!fields.containsKey(defaultEntry.getKey())) {
                fields.put(defaultEntry.getKey(), defaultEntry.getValue());
            }
        }

        // Procesar todos los fields
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String fieldName = entry.getKey();
            String fieldValue = entry.getValue();

            if (fieldValue != null && !fieldValue.trim().isEmpty()) {
                Map<String, Object> fieldData = new HashMap<>();
                fieldData.put("name", fieldName);
                fieldData.put("type", fieldTypes.getOrDefault(fieldName, "string"));
                fieldData.put("value", fieldValue);
                fieldsPayload.add(fieldData);
            }
        }

        return fieldsPayload;
    }

    /**
     * Log de valores asignados para debugging
     */
    private void logAssignedValues(Long tenantId, Map<String, Object> payload) {
        logger.info("===== Tenant {}: VALORES ASIGNADOS AL CREAR RIDER =====", tenantId);
        logger.info("Campos principales:");
        logger.info("  - name: {}", payload.get("name"));
        logger.info("  - email: {}", payload.get("email"));
        logger.info("  - phone_number: {}", payload.get("phone_number"));
        logger.info("  - bank_data: {}", payload.getOrDefault("bank_data", "NULL (no incluido)"));
        logger.info("  - reporting_to: {}", payload.getOrDefault("reporting_to", "NULL (no incluido)"));
        logger.info("  - city_id: {}", payload.getOrDefault("city_id", "NULL (no incluido)"));

        @SuppressWarnings("unchecked")
        Map<String, Object> contract = (Map<String, Object>) payload.get("contract");
        logger.info("Contrato:");
        logger.info("  - contract_id: {}", contract.get("contract_id"));
        logger.info("  - start_at: {}", contract.get("start_at"));
        logger.info("  - job_title: {}", contract.get("job_title"));
        logger.info("  - city_id: {}", contract.get("city_id"));

        logger.info("Arrays opcionales:");
        logger.info("  - vehicle_type_ids: {}", payload.get("vehicle_type_ids"));
        logger.info("  - starting_point_ids: {}",
                payload.getOrDefault("starting_point_ids", "[] (no incluido)"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) payload.get("fields");
        if (fields != null) {
            logger.info("Fields incluidos: {}", fields.size());
            fields.forEach(f -> logger.debug("  - {}: {}", f.get("name"), f.get("value")));
        } else {
            logger.info("Fields: [] (no incluido)");
        }
    }

    private String formatDateTime(String dateTime) {
        if (dateTime == null) {
            return LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME) + "Z";
        }

        if (dateTime.contains("T") && dateTime.endsWith("Z")) {
            return dateTime;
        }

        if (!dateTime.contains("T")) {
            return dateTime + "T00:00:00Z";
        }

        if (!dateTime.endsWith("Z")) {
            return dateTime + "Z";
        }

        return dateTime;
    }
}