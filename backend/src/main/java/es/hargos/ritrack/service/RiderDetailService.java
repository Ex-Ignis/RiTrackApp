package es.hargos.ritrack.service;

import es.hargos.ritrack.client.GlovoClient;
import es.hargos.ritrack.dto.RiderDetailDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Servicio para obtener información completa de riders
 * Combina datos de Rooster API (estáticos) y Live API (dinámicos)
 */
@Service
public class RiderDetailService {

    private static final Logger logger = LoggerFactory.getLogger(RiderDetailService.class);

    private final GlovoClient glovoClient;

    @Autowired
    public RiderDetailService(GlovoClient glovoClient) {
        this.glovoClient = glovoClient;
    }

    /**
     * Obtiene detalles completos de un rider combinando Rooster y Live APIs
     *
     * @param tenantId ID del tenant
     * @param riderId ID del rider (employee_id)
     * @return RiderDetailDto con toda la información disponible
     */
    public RiderDetailDto getRiderCompleteDetails(Long tenantId, Integer riderId) {
        logger.info("Tenant {}: Obteniendo detalles completos para rider {}", tenantId, riderId);

        try {
            // PASO 1: Obtener datos de Rooster API
            Object roosterData = glovoClient.getEmployeeById(tenantId, riderId);

            if (roosterData == null) {
                logger.warn("Tenant {}: No se encontraron datos de Rooster para rider {}", tenantId, riderId);
                return createEmptyRiderDetail(riderId, "NONE");
            }

            // PASO 2: Obtener datos de Live API directamente sin city_id
            Object liveData = glovoClient.getRiderLiveData(tenantId, riderId);

            // PASO 3: Combinar datos de ambas fuentes
            RiderDetailDto riderDetail = combineRoosterAndLiveData(roosterData, liveData);

            logger.info("Tenant {}: Detalles obtenidos exitosamente para rider {}: fuente={}, completo={}",
                    tenantId, riderId, riderDetail.getDataSource(), riderDetail.getIsDataComplete());

            return riderDetail;

        } catch (Exception e) {
            logger.error("Tenant {}: Error obteniendo detalles del rider {}: {}", tenantId, riderId, e.getMessage(), e);
            return createEmptyRiderDetail(riderId, "ERROR");
        }
    }

    /**
     * Combina datos de Rooster y Live APIs en un único DTO
     */
    private RiderDetailDto combineRoosterAndLiveData(Object roosterData, Object liveData) {
        RiderDetailDto.RiderDetailDtoBuilder builder = RiderDetailDto.builder();

        // Procesar datos de Rooster
        processRoosterData(builder, roosterData);

        // Procesar datos de Live si están disponibles
        if (liveData != null) {
            processLiveData(builder, liveData);
            builder.dataSource("FULL");
            builder.isDataComplete(true);
        } else {
            builder.dataSource("ROOSTER_ONLY");
            builder.isDataComplete(false);
        }

        builder.lastUpdated(LocalDateTime.now());

        return builder.build();
    }

    /**
     * Procesa datos de Rooster API y los agrega al builder
     */
    private void processRoosterData(RiderDetailDto.RiderDetailDtoBuilder builder, Object roosterData) {
        if (!(roosterData instanceof Map)) {
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> dataMap = (Map<String, Object>) roosterData;

        // Datos básicos del rider
        builder.riderId((Integer) dataMap.get("id"));
        builder.name((String) dataMap.get("name"));
        builder.email((String) dataMap.get("email"));
        builder.phone((String) dataMap.get("phone_number"));
        builder.bankData((String) dataMap.get("bank_data"));

        // Buscar campos adicionales en fields
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) dataMap.get("fields");
        if (fields != null) {
            for (Map<String, Object> field : fields) {
                String fieldName = (String) field.get("name");
                String fieldValue = (String) field.get("value");

                switch (fieldName) {
                    case "id_number":
                    case "dni":
                        builder.dni(fieldValue);
                        break;
                    case "birth_date":
                    case "date_of_birth":
                        builder.birthDate(fieldValue);
                        break;
                    case "operational_phone_number":
                        builder.operationalPhoneNumber(fieldValue);
                        break;
                    case "address_city":
                        builder.addressCity(fieldValue);
                        break;
                    case "glovo_id":
                        builder.glovoId(fieldValue);
                        break;
                    case "iban":
                        builder.iban(fieldValue);
                        break;
                    case "irpf":
                        builder.irpf(fieldValue);
                        break;
                    case "religion":
                        builder.religion(fieldValue);
                        break;
                    case "hasbox":
                        builder.hasBox("TRUE".equalsIgnoreCase(fieldValue));
                        break;
                    case "material_received":
                        builder.materialReceived("TRUE".equalsIgnoreCase(fieldValue));
                        break;
                    case "mcc":
                        builder.mcc("TRUE".equalsIgnoreCase(fieldValue));
                        break;
                    case "referral_url":
                        builder.referralUrl(fieldValue);
                        break;
                    case "short_referral_url":
                        builder.shortReferralUrl(fieldValue);
                        break;
                }
            }
        }

        // Procesar contratos
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contracts = (List<Map<String, Object>>) dataMap.get("contracts");
        if (contracts != null && !contracts.isEmpty()) {
            // Buscar contrato activo
            Map<String, Object> activeContract = null;
            for (Map<String, Object> contract : contracts) {
                Boolean currentlyActive = (Boolean) contract.get("currently_active");
                if (Boolean.TRUE.equals(currentlyActive)) {
                    activeContract = contract;
                    break;
                }
            }

            // Si no hay contrato activo, usar el más reciente
            if (activeContract == null) {
                activeContract = contracts.get(0);
            }

            processContract(builder, activeContract);
        }

        // Procesar datos adicionales del nivel principal
        Integer batchNumber = (Integer) dataMap.get("batch_number");
        if (batchNumber != null) {
            builder.batchNumber(batchNumber);
        }

        Integer reportingTo = (Integer) dataMap.get("reporting_to");
        if (reportingTo != null) {
            builder.reportingTo(reportingTo);
        }

// Procesar fechas de creación/actualización
        String createdAt = (String) dataMap.get("created_at");
        if (createdAt != null) {
            builder.createdAt(formatDateTimeString(createdAt));
        }

        String updatedAt = (String) dataMap.get("updated_at");
        if (updatedAt != null) {
            builder.updatedAt(formatDateTimeString(updatedAt));
        }

        // Procesar vehículos asignados
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> vehicles = (List<Map<String, Object>>) dataMap.get("vehicles");
        if (vehicles != null) {
            List<RiderDetailDto.VehicleTypeInfo> vehicleInfos = vehicles.stream()
                    .map(this::mapVehicleType)
                    .collect(Collectors.toList());
            builder.assignedVehicles(vehicleInfos);
        }

        // Procesar puntos de inicio
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> startingPoints = (List<Map<String, Object>>) dataMap.get("starting_points");
        if (startingPoints != null) {
            List<RiderDetailDto.StartingPointInfo> startingPointInfos = startingPoints.stream()
                    .map(this::mapStartingPoint)
                    .collect(Collectors.toList());
            builder.startingPoints(startingPointInfos);
        }
    }

    /**
     * Procesa información del contrato
     */
    private void processContract(RiderDetailDto.RiderDetailDtoBuilder builder, Map<String, Object> contractData) {
        if (contractData == null) return;

        // Información del contrato
        @SuppressWarnings("unchecked")
        Map<String, Object> contract = (Map<String, Object>) contractData.get("contract");
        if (contract != null) {
            builder.contractType((String) contract.get("type"));
            builder.contractName((String) contract.get("name"));
            builder.companyId((Integer) contract.get("company_id"));
        }

        // Información adicional del contrato
        builder.jobTitle((String) contractData.get("job_title"));
        builder.cityId((Integer) contractData.get("city_id"));

        // Fechas del contrato
        String startAt = (String) contractData.get("start_at");
        String endAt = (String) contractData.get("end_at");

        if (startAt != null) {
            builder.contractStartDate(formatDateString(startAt));
        }
        if (endAt != null) {
            builder.contractEndDate(formatDateString(endAt));
        }
    }

    /**
     * Procesa datos de Live API y los agrega al builder
     */
    private void processLiveData(RiderDetailDto.RiderDetailDtoBuilder builder, Object liveData) {
        if (!(liveData instanceof Map)) {
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> dataMap = (Map<String, Object>) liveData;

        // Estado actual
        builder.status((String) dataMap.get("status"));

        // Metadata del estado
        @SuppressWarnings("unchecked")
        Map<String, Object> statusMetadata = (Map<String, Object>) dataMap.get("status_metadata");
        if (statusMetadata != null) {
            builder.statusReason((String) statusMetadata.get("reason"));
            builder.statusPerformedBy((String) statusMetadata.get("performed_by"));
        }

        // Vehículo en uso actual
        @SuppressWarnings("unchecked")
        Map<String, Object> vehicle = (Map<String, Object>) dataMap.get("vehicle");
        if (vehicle != null) {
            RiderDetailDto.VehicleInUse vehicleInUse = RiderDetailDto.VehicleInUse.builder()
                    .id((Integer) vehicle.get("id"))
                    .name((String) vehicle.get("name"))
                    .icon((String) vehicle.get("icon"))
                    .defaultSpeed(getDoubleValue(vehicle.get("default_speed")))
                    .profile((String) vehicle.get("profile"))
                    .build();
            builder.currentVehicle(vehicleInUse);
        }

        // Información de billetera
        @SuppressWarnings("unchecked")
        Map<String, Object> walletInfo = (Map<String, Object>) dataMap.get("wallet_info");
        if (walletInfo != null) {
            RiderDetailDto.WalletInfo wallet = RiderDetailDto.WalletInfo.builder()
                    .balance(getDoubleValue(walletInfo.get("balance")))
                    .limitStatus((String) walletInfo.get("limit_status"))
                    .build();
            builder.wallet(wallet);
        }

        // Información de entregas - ACTUALIZADO con nuevos campos
        @SuppressWarnings("unchecked")
        Map<String, Object> deliveriesInfo = (Map<String, Object>) dataMap.get("deliveries_info");
        if (deliveriesInfo != null) {
            Boolean hasActiveDeliveries = (Boolean) deliveriesInfo.get("has_active_deliveries");

            // Procesar latest_deliveries para obtener direcciones de entregas activas
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> latestDeliveries = (List<Map<String, Object>>) deliveriesInfo.get("latest_deliveries");
            List<String> activeDropoffAddresses = new ArrayList<>();
            Integer activeDeliveryId = null;

            if (latestDeliveries != null && !latestDeliveries.isEmpty()) {
                for (Map<String, Object> delivery : latestDeliveries) {
                    String status = (String) delivery.get("status");


                    if (!"completed".equals(status) && !"cancelled".equals(status)) {
                        String dropoffAddress = (String) delivery.get("dropoff_address");
                        if (dropoffAddress != null && !dropoffAddress.trim().isEmpty()) {
                            activeDropoffAddresses.add(dropoffAddress);
                        }

                        // Tomar el primer delivery_id activo
                        if (activeDeliveryId == null) {
                            activeDeliveryId = (Integer) delivery.get("delivery_id");
                        }
                    }
                }
            }

            RiderDetailDto.DeliveriesInfo deliveries = RiderDetailDto.DeliveriesInfo.builder()
                    .hasActiveDelivery(hasActiveDeliveries)
                    .activeDeliveryId(activeDeliveryId)
                    .activeDropoffAddresses(activeDropoffAddresses)
                    .completedDeliveriesCount((Integer) deliveriesInfo.get("completed_deliveries_count"))
                    .cancelledDeliveriesCount((Integer) deliveriesInfo.get("cancelled_deliveries_count"))
                    .cancelledByIssueService((Integer) deliveriesInfo.get("cancelled_by_issue_service_action"))
                    .cancelledByOtherReasons((Integer) deliveriesInfo.get("cancelled_by_other_cancellation_reasons"))
                    .acceptedDeliveriesCount((Integer) deliveriesInfo.get("accepted_deliveries_count"))
                    .totalPickupWaitTime((Integer) deliveriesInfo.get("total_pickup_wait_time"))
                    .totalDropoffWaitTime((Integer) deliveriesInfo.get("total_dropoff_wait_time"))
                    .build();
            builder.deliveries(deliveries);
        }

        // Métricas de rendimiento
        @SuppressWarnings("unchecked")
        Map<String, Object> performance = (Map<String, Object>) dataMap.get("performance");
        if (performance != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> timeSpent = (Map<String, Object>) performance.get("time_spent");

            RiderDetailDto.TimeSpentInfo timeSpentInfo = null;
            if (timeSpent != null) {
                timeSpentInfo = RiderDetailDto.TimeSpentInfo.builder()
                        .workedSeconds((Integer) timeSpent.get("worked_seconds"))
                        .lateSeconds((Integer) timeSpent.get("late_seconds"))
                        .breakSeconds((Integer) timeSpent.get("break_seconds"))
                        .numberOfBreaks((Integer) timeSpent.get("number_of_breaks"))
                        .build();
            }

            RiderDetailDto.PerformanceMetrics metrics = RiderDetailDto.PerformanceMetrics.builder()
                    .utilizationRate(getDoubleValue(performance.get("utilization_rate")))
                    .reassignmentRate(getDoubleValue(performance.get("reassignment_rate")))
                    .acceptanceRate(getDoubleValue(performance.get("acceptance_rate")))
                    .timeSpent(timeSpentInfo)
                    .build();

            builder.performance(metrics);
        }

        // Información del turno actual
        String shiftStarted = (String) dataMap.get("active_shift_started_at");
        String shiftEnded = (String) dataMap.get("active_shift_ended_at");

        if (shiftStarted != null) {
            builder.activeShiftStartedAt(formatDateTimeString(shiftStarted));
        }
        if (shiftEnded != null) {
            builder.activeShiftEndedAt(formatDateTimeString(shiftEnded));
        }

        // Ubicación actual
        @SuppressWarnings("unchecked")
        Map<String, Object> currentLocation = (Map<String, Object>) dataMap.get("current_location");
        if (currentLocation != null) {
            RiderDetailDto.LocationInfo location = RiderDetailDto.LocationInfo.builder()
                    .latitude(getDoubleValue(currentLocation.get("latitude")))
                    .longitude(getDoubleValue(currentLocation.get("longitude")))
                    .accuracy(getDoubleValue(currentLocation.get("accuracy")))
                    .locationUpdatedAt(formatDateTimeString((String) currentLocation.get("location_updated_at")))
                    .build();
            builder.currentLocation(location);
        }

        // Información adicional del nuevo formato
        // ⚠️ IMPORTANTE: Live API devuelve zone.id (ej: 305) que NO es lo mismo que city_id (ej: 804)
        // cityId ya fue asignado correctamente desde Rooster API (active_contract.city_id)
        // Solo tomamos cityName de zone.name, NUNCA sobrescribir cityId con zone.id
        @SuppressWarnings("unchecked")
        Map<String, Object> zone = (Map<String, Object>) dataMap.get("zone");
        if (zone != null) {
            // ✅ Solo asignar cityName, NO cityId
            builder.cityName((String) zone.get("name"));
            // ❌ NO HACER: builder.cityId((Integer) zone.get("id"));
            //    zone.id es el ID de la zona (305), no el city_id correcto (804)
        }

        // Company ID
        Integer companyId = (Integer) dataMap.get("company_id");
        if (companyId != null) {
            builder.companyId(companyId);
        }
    }

    /**
     * Mapea datos de vehículo
     */
    private RiderDetailDto.VehicleTypeInfo mapVehicleType(Map<String, Object> vehicleData) {
        return RiderDetailDto.VehicleTypeInfo.builder()
                .vehicleTypeId((Integer) vehicleData.get("vehicle_type_id"))
                .vehicleTypeName((String) vehicleData.get("vehicle_type_name"))
                .icon((String) vehicleData.get("icon"))
                .active((Boolean) vehicleData.get("active"))
                .build();
    }

    /**
     * Mapea datos de punto de inicio
     */
    private RiderDetailDto.StartingPointInfo mapStartingPoint(Map<String, Object> startingPointData) {
        return RiderDetailDto.StartingPointInfo.builder()
                .id((Integer) startingPointData.get("id"))
                .name((String) startingPointData.get("name"))
                .cityId((Integer) startingPointData.get("city_id"))
                .build();
    }

    /**
     * Crea un RiderDetail solo con datos de Rooster
     */
    private RiderDetailDto createRiderDetailFromRoosterOnly(Object roosterData) {
        RiderDetailDto.RiderDetailDtoBuilder builder = RiderDetailDto.builder();

        processRoosterData(builder, roosterData);

        builder.dataSource("ROOSTER_ONLY");
        builder.isDataComplete(false);
        builder.lastUpdated(LocalDateTime.now());

        return builder.build();
    }

    /**
     * Crea un RiderDetail vacío
     */
    private RiderDetailDto createEmptyRiderDetail(Integer riderId, String dataSource) {
        return RiderDetailDto.builder()
                .riderId(riderId)
                .dataSource(dataSource)
                .isDataComplete(false)
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    /**
     * Convierte un objeto a Double de forma segura
     */
    private Double getDoubleValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Formatea una fecha string a formato yyyy-MM-dd
     */
    private String formatDateString(String dateString) {
        if (dateString == null) {
            return null;
        }
        try {
            // Si es un timestamp ISO
            if (dateString.contains("T")) {
                LocalDateTime dateTime = LocalDateTime.parse(dateString.substring(0, 19));
                return dateTime.toLocalDate().toString();
            }
            return dateString;
        } catch (Exception e) {
            logger.debug("Error formateando fecha: {}", dateString);
            return dateString;
        }
    }

    /**
     * Formatea un datetime string a formato yyyy-MM-dd HH:mm:ss
     */
    private String formatDateTimeString(String dateTimeString) {
        if (dateTimeString == null) {
            return null;
        }
        try {
            // Parsear el timestamp ISO
            LocalDateTime dateTime = LocalDateTime.parse(
                    dateTimeString.substring(0, Math.min(dateTimeString.length(), 19))
            );
            return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            logger.debug("Error formateando datetime: {}", dateTimeString);
            return dateTimeString;
        }
    }

    /**
     * Obtiene información básica del rider desde la ciudad proporcionada
     * Útil cuando el frontend ya conoce el city_id
     */
    public RiderDetailDto getRiderDetailsByCityId(Long tenantId, Integer riderId, Integer cityId) {
        logger.info("Tenant {}: Obteniendo detalles para rider {} (cityId {} ignorado)", tenantId, riderId, cityId);

        try {
            // Obtener datos de ambas APIs (cityId ya no es necesario para Live)
            Object roosterData = glovoClient.getEmployeeById(tenantId, riderId);
            Object liveData = glovoClient.getRiderLiveData(tenantId, riderId);

            // Combinar datos
            if (roosterData == null && liveData == null) {
                return createEmptyRiderDetail(riderId, "NONE");
            }

            if (roosterData != null && liveData != null) {
                return combineRoosterAndLiveData(roosterData, liveData);
            }

            if (roosterData != null) {
                return createRiderDetailFromRoosterOnly(roosterData);
            }

            // Solo datos de Live
            return createRiderDetailFromLiveOnly(liveData, riderId);

        } catch (Exception e) {
            logger.error("Tenant {}: Error obteniendo detalles del rider {}: {}", tenantId, riderId, e.getMessage(), e);
            return createEmptyRiderDetail(riderId, "ERROR");
        }
    }

    /**
     * Crea un RiderDetail solo con datos de Live API (caso raro)
     */
    private RiderDetailDto createRiderDetailFromLiveOnly(Object liveData, Integer riderId) {
        RiderDetailDto.RiderDetailDtoBuilder builder = RiderDetailDto.builder();

        builder.riderId(riderId);
        processLiveData(builder, liveData);

        // Extraer nombre del Live API si está disponible
        if (liveData instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> dataMap = (Map<String, Object>) liveData;
            builder.name((String) dataMap.get("name"));
            builder.phone((String) dataMap.get("phone_number"));
        }

        builder.dataSource("LIVE_ONLY");
        builder.isDataComplete(false);
        builder.lastUpdated(LocalDateTime.now());

        return builder.build();
    }

    /**
     * Obtiene solo el cityId del rider desde Rooster API
     * Útil para operaciones que solo necesitan saber la ciudad (ej: desbloquear)
     *
     * @param tenantId ID del tenant
     * @param riderId ID del rider
     * @return cityId del rider o null si no se encuentra
     * @throws Exception si hay error en la consulta
     */
    public Integer getRiderCityId(Long tenantId, Integer riderId) throws Exception {
        logger.info("Tenant {}: Obteniendo cityId para rider {}", tenantId, riderId);

        try {
            Object roosterData = glovoClient.getEmployeeById(tenantId, riderId);

            if (roosterData == null) {
                logger.warn("Tenant {}: No se encontró rider {} en Rooster API", tenantId, riderId);
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> dataMap = (Map<String, Object>) roosterData;

            // ✅ Extraer city_id del nivel raíz
            Integer cityId = (Integer) dataMap.get("city_id");

            if (cityId != null) {
                logger.info("Tenant {}: Rider {} tiene cityId: {}", tenantId, riderId, cityId);
                return cityId;
            }

            logger.warn("Tenant {}: Rider {} no tiene city_id", tenantId, riderId);
            return null;

        } catch (Exception e) {
            logger.error("Tenant {}: Error obteniendo cityId para rider {}: {}",
                tenantId, riderId, e.getMessage());
            throw e;
        }
    }
}