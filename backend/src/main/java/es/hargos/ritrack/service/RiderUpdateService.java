package es.hargos.ritrack.service;

import es.hargos.ritrack.client.GlovoClient;
import es.hargos.ritrack.dto.RiderUpdateDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RiderUpdateService {

    private static final Logger logger = LoggerFactory.getLogger(RiderUpdateService.class);

    private final GlovoClient glovoClient;
    private final RoosterCacheService roosterCache;

    @Autowired
    public RiderUpdateService(GlovoClient glovoClient, RoosterCacheService roosterCache) {
        this.glovoClient = glovoClient;
        this.roosterCache = roosterCache;
    }

    public Map<String, Object> updateRider(Long tenantId, Integer riderId, RiderUpdateDto updateData) throws Exception {
        logger.info("Tenant {}: Actualizando rider {} con datos parciales", tenantId, riderId);

        // Obtener datos actuales del rider
        Object currentDataObj = glovoClient.obtenerEmpleadoPorId(tenantId, riderId);
        if (currentDataObj == null) {
            throw new RuntimeException("Tenant " + tenantId + ": Rider no encontrado: " + riderId);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> currentData = (Map<String, Object>) currentDataObj;

        // Construir payload completo con merge de datos
        Map<String, Object> payload = buildUpdatePayload(currentData, updateData);

        // Enviar actualización - el error se maneja en GlovoClient
        Object result = glovoClient.actualizarEmpleado(tenantId, riderId, payload);

        // Limpiar caché para reflejar cambios inmediatamente
        roosterCache.clearCache();
        logger.info("Tenant {}: Cache limpiado después de actualización de rider {}", tenantId, riderId);

        return (Map<String, Object>) result;
    }

    private Map<String, Object> buildUpdatePayload(Map<String, Object> currentData, RiderUpdateDto updateData) {
        Map<String, Object> payload = new HashMap<>();

        // Campos requeridos - usar nuevo valor si existe, sino mantener actual
        payload.put("name", updateData.getName() != null ?
                updateData.getName() : currentData.get("name"));

        payload.put("email", updateData.getEmail() != null ?
                updateData.getEmail() : currentData.get("email"));

        payload.put("phone_number", updateData.getPhone() != null ?
                updateData.getPhone() : currentData.get("phone_number"));

        // Campos opcionales
        if (updateData.getBankData() != null || currentData.get("bank_data") != null) {
            payload.put("bank_data", updateData.getBankData() != null ?
                    updateData.getBankData() : currentData.get("bank_data"));
        }

        if (updateData.getReportingTo() != null || currentData.get("reporting_to") != null) {
            payload.put("reporting_to", updateData.getReportingTo() != null ?
                    updateData.getReportingTo() : currentData.get("reporting_to"));
        }

        // Password - solo incluir si se proporciona uno nuevo
        // NO podemos obtener la contraseña actual, así que solo la incluimos si se quiere cambiar
        if (updateData.getPassword() != null && !updateData.getPassword().isEmpty()) {
            payload.put("password", updateData.getPassword());
            logger.info("Incluida nueva contraseña para rider");
        }

        // Preparar fields para actualización automática de operational_phone_number
        Map<String, String> fieldsToUpdate = new HashMap<>();

        // Si se actualizó el teléfono y no se especificó operational_phone_number, actualizarlo automáticamente
        if (updateData.getPhone() != null) {
            boolean operationalPhoneSpecified = false;

            if (updateData.getFields() != null) {
                fieldsToUpdate.putAll(updateData.getFields());
                operationalPhoneSpecified = updateData.getFields().containsKey("operational_phone_number");
            }

            // Solo actualizar operational_phone_number si no se especificó explícitamente
            if (!operationalPhoneSpecified) {
                fieldsToUpdate.put("operational_phone_number", updateData.getPhone());
                logger.info("Actualizando operational_phone_number automáticamente con el nuevo teléfono");
            }
        } else if (updateData.getFields() != null) {
            fieldsToUpdate.putAll(updateData.getFields());
        }


        // Procesar fields - mantener existentes y actualizar/añadir nuevos
        List<Map<String, Object>> fieldsPayload = processFields(currentData,  fieldsToUpdate);
        if (!fieldsPayload.isEmpty()) {
            payload.put("fields", fieldsPayload);
        }

        return payload;
    }

    private List<Map<String, Object>> processFields(Map<String, Object> currentData, Map<String, String> updatedFields) {
        List<Map<String, Object>> fieldsPayload = new ArrayList<>();

        // Obtener fields actuales
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> currentFields = (List<Map<String, Object>>) currentData.get("fields");

        Map<String, Map<String, Object>> fieldsMap = new HashMap<>();

        // Primero, cargar todos los fields actuales
        if (currentFields != null) {
            for (Map<String, Object> field : currentFields) {
                String fieldName = (String) field.get("name");
                fieldsMap.put(fieldName, field);
            }
        }

        // Actualizar con nuevos valores si existen
        if (updatedFields != null) {
            for (Map.Entry<String, String> entry : updatedFields.entrySet()) {
                String fieldName = entry.getKey();
                String fieldValue = entry.getValue();

                Map<String, Object> fieldData = fieldsMap.getOrDefault(fieldName, new HashMap<>());
                fieldData.put("name", fieldName);
                fieldData.put("value", fieldValue);

                // Mantener type si existe, sino usar string por defecto
                if (!fieldData.containsKey("type")) {
                    fieldData.put("type", determineFieldType(fieldName));
                }

                fieldsMap.put(fieldName, fieldData);
            }
        }

        // Convertir mapa a lista
        fieldsPayload.addAll(fieldsMap.values());

        return fieldsPayload;
    }

    private String determineFieldType(String fieldName) {
        // Determinar tipo basado en el nombre del campo
        if ("birth_date".equals(fieldName)) {
            return "date";
        }
        if ("hasbox".equals(fieldName) || "material_received".equals(fieldName) || "mcc".equals(fieldName)) {
            return "boolean";
        }
        return "string";
    }
}