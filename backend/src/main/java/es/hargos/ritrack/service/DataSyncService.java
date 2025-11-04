package es.hargos.ritrack.service;

import es.hargos.ritrack.client.GlovoClient;
import es.hargos.ritrack.context.TenantContext;
import es.hargos.ritrack.entity.RiderEntity;
import es.hargos.ritrack.entity.DailyDeliveryEntity;
import es.hargos.ritrack.entity.TenantEntity;
import es.hargos.ritrack.repository.RiderRepository;
import es.hargos.ritrack.repository.DailyDeliveryRepository;
import es.hargos.ritrack.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class DataSyncService {

    private static final Logger logger = LoggerFactory.getLogger(DataSyncService.class);

    @Autowired
    private GlovoClient glovoClient;

    @Autowired
    private RiderRepository riderRepository;

    @Autowired
    private DailyDeliveryRepository dailyDeliveryRepository;

    @Autowired
    private RoosterCacheService roosterCache;

    @Autowired
    private RiderFilterService riderFilterService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private TenantOnboardingService onboardingService;

    @Value("${contract.id:344}")
    private Integer contractId;

    @Value("${company.id:42}")
    private Integer companyId;

    /**
     * Sincronización de Rooster cada 30 minutos
     * Actualiza datos básicos de riders desde el caché de Rooster
     *
     * MULTI-TENANT: Itera por todos los tenants activos y establece
     * el contexto de tenant antes de cada sincronización.
     */
    @Scheduled(fixedRate = 1800000) // 30 minutos
    public void syncRoosterData() {
        logger.debug("Verificando tenants para sincronización de Rooster...");

        try {
            // Obtener todos los tenants activos
            List<TenantEntity> activeTenants = tenantRepository.findByIsActive(true);

            if (activeTenants.isEmpty()) {
                logger.debug("No se encontraron tenants activos");
                return;
            }

            // Filtrar solo tenants que estén completamente configurados (tienen credenciales)
            List<TenantEntity> readyTenants = activeTenants.stream()
                    .filter(tenant -> onboardingService.isTenantReady(tenant.getId()))
                    .collect(Collectors.toList());

            if (readyTenants.isEmpty()) {
                logger.debug("Ningún tenant está listo para sincronización (sin credenciales configuradas)");
                return;
            }

            logger.info("Sincronizando Rooster para {} tenants configurados (de {} activos)",
                    readyTenants.size(), activeTenants.size());

            // Procesar cada tenant configurado
            for (TenantEntity tenant : readyTenants) {
                try {
                    // Establecer contexto de tenant para que Hibernate use el schema correcto
                    TenantContext.TenantInfo tenantInfo = TenantContext.TenantInfo.builder()
                            .tenantIds(Collections.singletonList(tenant.getId()))
                            .tenantNames(Collections.singletonList(tenant.getName()))
                            .schemaNames(Collections.singletonList(tenant.getSchemaName()))
                            .build();

                    TenantContext.setCurrentContext(tenantInfo);

                    logger.debug("Tenant {}: Sincronizando datos de Rooster", tenant.getName());
                    syncRidersFromRooster(tenant.getId());
                    logger.debug("Tenant {}: Sincronización de Rooster completada", tenant.getName());

                } catch (Exception e) {
                    logger.error("Tenant {}: Error en sincronización de Rooster: {}",
                            tenant.getName(), e.getMessage(), e);
                } finally {
                    // IMPORTANTE: Limpiar contexto después de cada tenant
                    TenantContext.clear();
                }
            }

            logger.debug("Sincronización de Rooster completada");

        } catch (Exception e) {
            logger.error("Error general en sincronización de Rooster: {}", e.getMessage(), e);
        }
    }

    /**
     * Sincronización de Live API cada 10 minutos
     * Actualiza entregas y estados en tiempo real
     *
     * MULTI-TENANT: Itera por todos los tenants activos y establece
     * el contexto de tenant antes de cada sincronización.
     */
    @Scheduled(fixedRate = 600000) // 10 minutos
    public void syncLiveData() {
        logger.debug("Verificando tenants para sincronización de Live...");

        try {
            // Obtener todos los tenants activos
            List<TenantEntity> activeTenants = tenantRepository.findByIsActive(true);

            if (activeTenants.isEmpty()) {
                logger.debug("No se encontraron tenants activos");
                return;
            }

            // Filtrar solo tenants que estén completamente configurados (tienen credenciales)
            List<TenantEntity> readyTenants = activeTenants.stream()
                    .filter(tenant -> onboardingService.isTenantReady(tenant.getId()))
                    .collect(Collectors.toList());

            if (readyTenants.isEmpty()) {
                logger.debug("Ningún tenant está listo para sincronización (sin credenciales configuradas)");
                return;
            }

            logger.info("Sincronizando Live para {} tenants configurados (de {} activos)",
                    readyTenants.size(), activeTenants.size());

            // Procesar cada tenant configurado
            for (TenantEntity tenant : readyTenants) {
                try {
                    // Establecer contexto de tenant para que Hibernate use el schema correcto
                    TenantContext.TenantInfo tenantInfo = TenantContext.TenantInfo.builder()
                            .tenantIds(Collections.singletonList(tenant.getId()))
                            .tenantNames(Collections.singletonList(tenant.getName()))
                            .schemaNames(Collections.singletonList(tenant.getSchemaName()))
                            .build();

                    TenantContext.setCurrentContext(tenantInfo);

                    logger.debug("Tenant {}: Sincronizando datos Live", tenant.getName());
                    syncDailyDeliveries(tenant.getId());
                    logger.debug("Tenant {}: Sincronización de Live completada", tenant.getName());

                } catch (Exception e) {
                    logger.error("Tenant {}: Error en sincronización de Live: {}",
                            tenant.getName(), e.getMessage(), e);
                } finally {
                    // IMPORTANTE: Limpiar contexto después de cada tenant
                    TenantContext.clear();
                }
            }

            logger.debug("Sincronización de Live completada");

        } catch (Exception e) {
            logger.error("Error general en sincronización de Live: {}", e.getMessage(), e);
        }
    }

    /**
     * Sincroniza riders desde Rooster Cache
     * REQUIRES_NEW ensures transaction starts AFTER TenantContext is set
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void syncRidersFromRooster(Long tenantId) {
        try {
            List<?> employees = roosterCache.getAllEmployees(tenantId);

            if (employees == null || employees.isEmpty()) {
                logger.warn("Tenant {}: No se encontraron empleados en cache de Rooster", tenantId);
                return;
            }

            int updated = 0;
            int created = 0;

            for (Object emp : employees) {
                if (emp instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> empMap = (Map<String, Object>) emp;

                    if (!belongsToCompany(empMap)) {
                        continue; // Saltar riders de otras empresas
                    }

                    Integer employeeIdInt = (Integer) empMap.get("id");
                    if (employeeIdInt == null) continue;
                    String employeeId = String.valueOf(employeeIdInt);

                    // Verificar si ya existe
                    Optional<RiderEntity> existing = riderRepository
                            .findByEmployeeId(employeeId);

                    RiderEntity rider;
                    if (existing.isPresent()) {
                        rider = existing.get();
                        updated++;
                    } else {
                        rider = new RiderEntity();
                        rider.setEmployeeId(employeeId);
                        created++;
                    }

                    // Actualizar datos básicos
                    rider.setFullName((String) empMap.get("name"));
                    rider.setEmail((String) empMap.get("email"));
                    rider.setPhone((String) empMap.get("phone_number"));

                    // Extraer ciudad y contrato
                    extractContractInfo(rider, empMap);

                    // Extraer DNI de fields si existe
                    extractFieldsInfo(rider, empMap);

                    riderRepository.save(rider);
                }
            }

            logger.info("Riders sincronizados - Creados: {}, Actualizados: {}", created, updated);

        } catch (Exception e) {
            logger.error("Error sincronizando riders desde Rooster: {}", e.getMessage());
        }
    }


    /**
     * Sincroniza entregas diarias desde Live API
     * REQUIRES_NEW ensures transaction starts AFTER TenantContext is set
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void syncDailyDeliveries(Long tenantId) {
        LocalDate today = LocalDate.now();

        // Cambiar la lógica: obtener TODOS los riders si no tienen status
        List<RiderEntity> ridersToSync;

        // Verificar si hay riders sin status (primera carga)
        long ridersWithoutStatus = riderRepository.findAll().stream()
                .filter(r -> r.getStatus() == null)
                .count();

        if (ridersWithoutStatus > 0) {
            logger.info("Primera sincronización: {} riders sin status", ridersWithoutStatus);
            ridersToSync = riderRepository.findAll(); // Sincronizar TODOS
        } else {
            // En sincronizaciones posteriores, solo los activos
            ridersToSync = riderRepository.findActiveRiders();
        }

        if (ridersToSync.isEmpty()) {
            logger.info("No hay riders para sincronizar");
            return;
        }

        logger.info("Sincronizando {} riders", ridersToSync.size());

        for (RiderEntity rider : ridersToSync) {
            try {
                // Obtener datos de Live para este rider
                Integer riderId = Integer.parseInt(rider.getEmployeeId());
                Map<String, Object> liveData = getRiderLiveData(tenantId, riderId, rider.getCityId());

                if (liveData != null) {
                    updateDailyDeliveryData(rider, liveData, today);
                } else {
                    // Si no hay datos en Live, al menos marcar como not_working
                    rider.setStatus("not_working");
                    riderRepository.save(rider);
                }

            } catch (Exception e) {
                logger.debug("Tenant {}: Error obteniendo datos live para rider {}: {}",
                        tenantId, rider.getEmployeeId(), e.getMessage());
            }
        }
    }

    /**
     * Actualiza o crea registro de entregas diarias Acumula pedidos durante el día
     */
    @Transactional
    private void updateDailyDeliveryData(RiderEntity rider, Map<String, Object> liveData, LocalDate date) {
        try {
            // Determinar la fecha efectiva basada en el inicio del turno
            String shiftStartedAt = (String) liveData.get("active_shift_started_at");
            LocalDateTime shiftStart = null;

            // Hacer la fecha final desde el principio
            final LocalDate effectiveDate;

            if (shiftStartedAt != null) {
                shiftStart = parseDateTime(shiftStartedAt);
                if (shiftStart != null) {
                    effectiveDate = shiftStart.toLocalDate();
                    logger.debug("Rider {} - Turno iniciado: {}, fecha efectiva: {}",
                            rider.getEmployeeId(), shiftStart, effectiveDate);
                } else {
                    effectiveDate = date;
                }
            } else {
                effectiveDate = date;
            }


            // Buscar o crear con la fecha EFECTIVA (del inicio del turno)
            DailyDeliveryEntity dailyDelivery = dailyDeliveryRepository
                    .findByRiderIdAndDate(rider.getEmployeeId(), effectiveDate)
                    .orElseGet(() -> {
                        DailyDeliveryEntity newDelivery = new DailyDeliveryEntity();
                        newDelivery.setRiderId(rider.getEmployeeId());
                        newDelivery.setDate(effectiveDate); // Usar fecha efectiva
                        newDelivery.setCompletedDeliveries(0);
                        newDelivery.setCancelledDeliveries(0);
                        newDelivery.setAcceptedDeliveries(0);
                        newDelivery.setTotalWorkedSeconds(0);
                        newDelivery.setTotalBreakSeconds(0);
                        newDelivery.setLastSessionDeliveries(0);
                        newDelivery.setLastUpdateTime(LocalDateTime.now());
                        return newDelivery;
                    });

            // Actualizar estado actual del rider
            String currentStatus = (String) liveData.get("status");
            if (currentStatus != null) {
                rider.setStatus(currentStatus);
                riderRepository.save(rider);
            }

            boolean isNewSession = false;

            // Extraer información de entregas del turno actual
            @SuppressWarnings("unchecked")
            Map<String, Object> deliveriesInfo = (Map<String, Object>) liveData.get("deliveries_info");

            if (deliveriesInfo != null) {
                Integer currentSessionCompleted = (Integer) deliveriesInfo.get("completed_deliveries_count");
                Integer currentSessionCancelled = (Integer) deliveriesInfo.get("cancelled_deliveries_count");
                Integer currentSessionAccepted = (Integer) deliveriesInfo.get("accepted_deliveries_count");

                // Detectar si es un nuevo turno
                if (shiftStart != null) {
                    LocalDateTime lastUpdate = dailyDelivery.getLastUpdateTime();

                    // Nuevo turno si: inicio después de última actualización
                    if (lastUpdate != null && shiftStart.isAfter(lastUpdate)) {
                        isNewSession = true;
                        logger.info("Nuevo turno detectado para rider {} a las {}",
                                rider.getEmployeeId(), shiftStart);
                    }
                }

                // También detectar por reset de contadores
                if (currentSessionCompleted != null &&
                        currentSessionCompleted < dailyDelivery.getLastSessionDeliveries()) {
                    isNewSession = true;
                    logger.info("Nuevo turno detectado para rider {} (reset de contadores)",
                            rider.getEmployeeId());
                }

                // ACTUALIZAR CONTADORES
                if (currentSessionCompleted != null && currentSessionCompleted >= 0) {
                    if (isNewSession) {
                        // NUEVO TURNO: Sumar al total del día
                        dailyDelivery.setCompletedDeliveries(
                                dailyDelivery.getCompletedDeliveries() + currentSessionCompleted);
                        dailyDelivery.setCancelledDeliveries(
                                dailyDelivery.getCancelledDeliveries() +
                                        (currentSessionCancelled != null ? currentSessionCancelled : 0));
                        dailyDelivery.setAcceptedDeliveries(
                                dailyDelivery.getAcceptedDeliveries() +
                                        (currentSessionAccepted != null ? currentSessionAccepted : 0));
                    } else {
                        // MISMO TURNO: Actualizar con el máximo valor visto
                        int previousTotal = dailyDelivery.getCompletedDeliveries() -
                                dailyDelivery.getLastSessionDeliveries();
                        dailyDelivery.setCompletedDeliveries(previousTotal + currentSessionCompleted);

                        if (currentSessionCancelled != null) {
                            int previousCancelled = dailyDelivery.getCancelledDeliveries() -
                                    (dailyDelivery.getLastSessionCancelled() != null ?
                                            dailyDelivery.getLastSessionCancelled() : 0);
                            dailyDelivery.setCancelledDeliveries(previousCancelled + currentSessionCancelled);
                        }

                        if (currentSessionAccepted != null) {
                            int previousAccepted = dailyDelivery.getAcceptedDeliveries() -
                                    (dailyDelivery.getLastSessionDeliveries() != null ?
                                            dailyDelivery.getLastSessionDeliveries() : 0);
                            dailyDelivery.setAcceptedDeliveries(previousAccepted + currentSessionAccepted);
                        }
                    }

                    // Guardar valores del turno actual
                    dailyDelivery.setLastSessionDeliveries(currentSessionCompleted);
                    dailyDelivery.setLastSessionCancelled(currentSessionCancelled);
                }
            }

            // Extraer métricas de rendimiento
            @SuppressWarnings("unchecked")
            Map<String, Object> performance = (Map<String, Object>) liveData.get("performance");
            if (performance != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> timeSpent = (Map<String, Object>) performance.get("time_spent");

                if (timeSpent != null) {
                    Integer workedSeconds = (Integer) timeSpent.get("worked_seconds");
                    Integer breakSeconds = (Integer) timeSpent.get("break_seconds");

                    if (isNewSession && workedSeconds != null) {
                        // Nuevo turno: sumar tiempos
                        dailyDelivery.setTotalWorkedSeconds(
                                dailyDelivery.getTotalWorkedSeconds() + workedSeconds);
                        dailyDelivery.setTotalBreakSeconds(
                                dailyDelivery.getTotalBreakSeconds() +
                                        (breakSeconds != null ? breakSeconds : 0));
                    } else if (workedSeconds != null) {
                        // Mismo turno: solo añadir la diferencia
                        Integer lastWorked = dailyDelivery.getLastSessionWorkedSeconds();
                        if (lastWorked == null) lastWorked = 0;

                        if (workedSeconds > lastWorked) {
                            int additionalTime = workedSeconds - lastWorked;
                            dailyDelivery.setTotalWorkedSeconds(
                                    dailyDelivery.getTotalWorkedSeconds() + additionalTime);
                        }
                    }

                    dailyDelivery.setLastSessionWorkedSeconds(workedSeconds);
                }

                dailyDelivery.setUtilizationRate(getDoubleValue(performance.get("utilization_rate")));
                dailyDelivery.setAcceptanceRate(getDoubleValue(performance.get("acceptance_rate")));
            }

            // Actualizar timestamp
            dailyDelivery.setLastUpdateTime(LocalDateTime.now());

            // Guardar solo si hay datos relevantes
            if (dailyDelivery.getCompletedDeliveries() > 0 ||
                    dailyDelivery.getTotalWorkedSeconds() > 0 ||
                    "working".equals(currentStatus)) {
                dailyDeliveryRepository.save(dailyDelivery);
                logger.debug("Rider {} - Fecha: {} - Total día: {} pedidos, Turno actual: {} pedidos",
                        rider.getEmployeeId(),
                        effectiveDate,
                        dailyDelivery.getCompletedDeliveries(),
                        dailyDelivery.getLastSessionDeliveries());
            }

        } catch (Exception e) {
            logger.error("Error actualizando entregas para rider {}: {}",
                    rider.getEmployeeId(), e.getMessage());
        }
    }

    private LocalDateTime parseDateTime(String dateTimeStr) {
        try {
            if (dateTimeStr == null) return null;
            // Remover la Z del final si existe y parsear
            if (dateTimeStr.endsWith("Z")) {
                dateTimeStr = dateTimeStr.substring(0, dateTimeStr.length() - 1);
            }
            return LocalDateTime.parse(dateTimeStr);
        } catch (Exception e) {
            logger.debug("Error parseando fecha: {}", dateTimeStr);
            return null;
        }
    }

    private boolean belongsToCompany(Map<String, Object> empMap) {
        @SuppressWarnings("unchecked")
        Map<String, Object> activeContract = (Map<String, Object>) empMap.get("active_contract");

        if (activeContract != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> contract = (Map<String, Object>) activeContract.get("contract");
            if (contract != null) {
                Integer foundContractId = (Integer) contract.get("id");
                return contractId.equals(foundContractId);
            }
        }
        return false;
    }

    /**
     * Obtiene datos de Live API para un rider específico
     */
    private Map<String, Object> getRiderLiveData(Long tenantId, Integer riderId, Integer cityId) {
        try {
            Object liveData = glovoClient.obtenerRiderLiveData(tenantId, riderId);

            if (liveData instanceof Map) {
                return (Map<String, Object>) liveData;
            }
            return null;
        } catch (Exception e) {
            logger.debug("Tenant {}: Error obteniendo datos live para rider {}: {}", tenantId, riderId, e.getMessage());
            return null;
        }
    }
    /**
     * Extrae información del contrato
     */
    private void extractContractInfo(RiderEntity rider, Map<String, Object> empMap) {
        @SuppressWarnings("unchecked")
        Map<String, Object> activeContract = (Map<String, Object>) empMap.get("active_contract");

        if (activeContract != null) {
            rider.setCityId((Integer) activeContract.get("city_id"));
            rider.setCityName((String) activeContract.get("city_name"));

            @SuppressWarnings("unchecked")
            Map<String, Object> contract = (Map<String, Object>) activeContract.get("contract");
            if (contract != null) {
                rider.setContractType((String) contract.get("type"));
            }
        }
    }

    /**
     * Extrae información de fields
     */
    private void extractFieldsInfo(RiderEntity rider, Map<String, Object> empMap) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) empMap.get("fields");

        if (fields != null) {
            for (Map<String, Object> field : fields) {
                String fieldName = (String) field.get("name");
                String fieldValue = (String) field.get("value");

                if ("id_number".equals(fieldName) || "dni".equals(fieldName)) {
                    rider.setDni(fieldValue);
                }
            }
        }
    }

    private Double getDoubleValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return null;
    }

    /**
     * Método manual para sincronizar todos los riders existentes
     * Llamar una vez al inicio para cargar datos históricos
     *
     * @param tenantId ID del tenant a sincronizar
     */
    public void initialDataLoad(Long tenantId) {
        logger.info("Tenant {}: Iniciando carga inicial de datos", tenantId);

        try {
            // Obtener información del tenant
            TenantEntity tenant = tenantRepository.findById(tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("Tenant no encontrado: " + tenantId));

            // Establecer contexto de tenant
            TenantContext.TenantInfo tenantInfo = TenantContext.TenantInfo.builder()
                    .tenantIds(Collections.singletonList(tenant.getId()))
                    .tenantNames(Collections.singletonList(tenant.getName()))
                    .schemaNames(Collections.singletonList(tenant.getSchemaName()))
                    .build();

            TenantContext.setCurrentContext(tenantInfo);

            // Ejecutar sincronización
            syncRidersFromRooster(tenantId);
            syncDailyDeliveries(tenantId);

            logger.info("Tenant {}: Carga inicial completada", tenant.getName());

        } catch (Exception e) {
            logger.error("Error en carga inicial de datos para tenant {}: {}", tenantId, e.getMessage(), e);
            throw e;
        } finally {
            // Limpiar contexto
            TenantContext.clear();
        }
    }
}