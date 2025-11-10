package es.hargos.ritrack.service;

import es.hargos.ritrack.client.GlovoClient;
import es.hargos.ritrack.dto.RiderLocationDto;
import es.hargos.ritrack.entity.TenantEntity;
import es.hargos.ritrack.repository.TenantRepository;
import es.hargos.ritrack.websocket.RiderLocationWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Servicio principal para gestión de ubicaciones de riders en tiempo real.
 *
 * MULTI-TENANT SUPPORT:
 * - El scheduled task itera por todos los tenants activos
 * - Cada tenant usa sus propias credenciales de Glovo API
 * - Cada tenant tiene su propio rate limit independiente
 * - Las ubicaciones se broadcast filtrando por tenant en WebSocket
 *
 * Responsabilidades:
 * - Actualización automática de ubicaciones cada 30 segundos
 * - Obtención de datos de riders por ciudad desde la API de Glovo
 * - Procesamiento y filtrado de ubicaciones válidas
 * - Envío de datos via WebSocket a clientes suscritos
 *
 * Flujo principal:
 * 1. Scheduler ejecuta updateAllTenantsLocations() cada 30 segundos
 * 2. Obtiene todos los tenants activos desde DB
 * 3. Para cada tenant, obtiene ubicaciones de sus ciudades
 * 4. Los datos se envían via WebSocket organizados por ciudad y tenant
 */
@Service
public class RiderLocationService {

    private static final Logger logger = LoggerFactory.getLogger(RiderLocationService.class);

    // ===============================================
    // CONFIGURACIÓN Y DEPENDENCIAS
    // ===============================================

    private final GlovoClient glovoClient;
    private final RiderLocationWebSocketHandler webSocketHandler;
    private final TenantRepository tenantRepository;
    private final TenantSettingsService tenantSettingsService;
    private final TenantOnboardingService onboardingService;

    @Value("${debug.mock-data.enabled:false}")
    private boolean mockDataEnabled;

    @Autowired
    public RiderLocationService(GlovoClient glovoClient,
                                 RiderLocationWebSocketHandler webSocketHandler,
                                 TenantRepository tenantRepository,
                                 TenantSettingsService tenantSettingsService,
                                 TenantOnboardingService onboardingService) {
        this.glovoClient = glovoClient;
        this.webSocketHandler = webSocketHandler;
        this.tenantRepository = tenantRepository;
        this.tenantSettingsService = tenantSettingsService;
        this.onboardingService = onboardingService;
    }

    // ===============================================
    // MÉTODOS PÚBLICOS PRINCIPALES - SCHEDULING
    // ===============================================

    /**
     * Método programado que se ejecuta cada 30 segundos para actualizar ubicaciones.
     * Itera por todos los tenants activos y actualiza sus ubicaciones.
     *
     * MULTI-TENANT: Cada tenant usa sus propias credenciales y rate limit.
     */
    @Scheduled(fixedRate = 30000) // 30 segundos
    public void updateAllTenantsLocations() {
        logger.debug("Verificando tenants para actualización de ubicaciones...");

        try {
            if (mockDataEnabled) {
                sendMockDataForTesting();
                return;
            }

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

            logger.info("Actualizando ubicaciones para {} tenants configurados (de {} activos)",
                    readyTenants.size(), activeTenants.size());

            // Procesar cada tenant configurado de forma asíncrona
            List<CompletableFuture<Void>> futures = readyTenants.stream()
                .map(tenant -> CompletableFuture.runAsync(() ->
                    updateTenantLocations(tenant.getId(), tenant.getName())
                ))
                .collect(Collectors.toList());

            // Esperar a que todos completen
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            logger.debug("Actualización de ubicaciones completada");

        } catch (Exception e) {
            logger.error("Error en actualización programada de ubicaciones: {}", e.getMessage(), e);
        }
    }

    /**
     * Actualiza ubicaciones para un tenant específico.
     *
     * @param tenantId Tenant ID
     * @param tenantName Tenant name (para logs)
     */
    private void updateTenantLocations(Long tenantId, String tenantName) {
        try {
            logger.info("Tenant {}: Actualizando ubicaciones...", tenantName);

            // Obtener ciudades activas desde configuración del tenant
            List<Integer> activeCityIds = tenantSettingsService.getActiveCityIds(tenantId);

            if (activeCityIds.isEmpty()) {
                logger.warn("Tenant {}: No tiene ciudades activas configuradas", tenantName);
                return;
            }

            // Procesar cada ciudad configurada para este tenant
            for (Integer cityId : activeCityIds) {
                try {
                    List<RiderLocationDto> locations = getRiderLocationsByCity(tenantId, cityId);
                    if (!locations.isEmpty()) {
                        // Broadcast via WebSocket (MULTI-TENANT: Filtra por tenant automáticamente)
                        webSocketHandler.broadcastRiderLocationsByCity(tenantId, cityId, locations);
                        logger.debug("Tenant {}, Ciudad {}: Enviadas {} ubicaciones",
                            tenantName, cityId, locations.size());
                    }

                } catch (Exception e) {
                    logger.error("Tenant {}, Ciudad {}: Error obteniendo ubicaciones: {}",
                        tenantName, cityId, e.getMessage());
                }
            }

        } catch (Exception e) {
            logger.error("Tenant {}: Error actualizando ubicaciones: {}",
                tenantName, e.getMessage(), e);
        }
    }

    // ===============================================
    // MÉTODOS PÚBLICOS - UBICACIONES POR CIUDAD
    // ===============================================

    // Límite de seguridad: 4 req/s (deja margen de 1 req/s del límite de 5 req/s de Glovo Live API)
    private static final int SAFE_REQ_PER_SECOND = 4;
    private static final long THROTTLE_DELAY_MS = 1000 / SAFE_REQ_PER_SECOND; // 250ms

    /**
     * Obtiene ubicaciones actuales de riders para una ciudad específica de un tenant.
     *
     * THROTTLING: Respeta límite de 4 req/s (margen de seguridad del límite de 5 req/s de Glovo).
     * Aplica delay de 250ms entre páginas para garantizar que no se exceda el rate limit.
     *
     * @param tenantId Tenant ID
     * @param cityId ID de la ciudad
     * @return Lista de ubicaciones de riders con coordenadas válidas
     */
    public List<RiderLocationDto> getRiderLocationsByCity(Long tenantId, Integer cityId) {
        logger.debug("Tenant {}, Ciudad {}: Obteniendo ubicaciones...", tenantId, cityId);

        try {
            List<RiderLocationDto> allLocations = new ArrayList<>();
            int page = 0;
            boolean hasMorePages = true;
            final int PAGE_SIZE = 100;
            final int MAX_PAGES = 50; // Límite de seguridad

            while (hasMorePages && page < MAX_PAGES) {
                long startTime = System.currentTimeMillis();

                // Llamada a GlovoClient con tenantId
                Object ridersData = glovoClient.getRidersByCity(
                    tenantId, cityId, page, PAGE_SIZE, "employee_id"
                );

                logger.debug("Tenant {}, Ciudad {}, Página {}: ridersData = {}", tenantId, cityId, page, ridersData);
                if (ridersData == null) {
                    break;
                }

                if (ridersData instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> responseMap = (Map<String, Object>) ridersData;

                    List<RiderLocationDto> pageLocations = extractLocationsFromRidersData(ridersData);
                    allLocations.addAll(pageLocations);

                    // Verificar si hay más páginas
                    Boolean isLast = (Boolean) responseMap.get("is_last");
                    hasMorePages = isLast != null && !isLast;

                    // Si no hay riders en esta página, parar
                    if (pageLocations.isEmpty()) {
                        hasMorePages = false;
                    }
                    page++;

                    // THROTTLING: Esperar entre páginas para respetar límite de 4 req/s
                    if (hasMorePages && page < MAX_PAGES) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        long sleepTime = THROTTLE_DELAY_MS - elapsed;

                        if (sleepTime > 0) {
                            try {
                                logger.debug("Tenant {}, Ciudad {}: Throttling {}ms antes de página {}",
                                    tenantId, cityId, sleepTime, page);
                                Thread.sleep(sleepTime);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                logger.warn("Tenant {}, Ciudad {}: Throttling interrumpido", tenantId, cityId);
                                hasMorePages = false;
                            }
                        }
                    }
                } else {
                    hasMorePages = false;
                }
            }

            if (page >= MAX_PAGES) {
                logger.warn("Tenant {}, Ciudad {}: Alcanzado límite máximo de páginas ({})",
                    tenantId, cityId, MAX_PAGES);
            }

            logger.debug("Tenant {}, Ciudad {}: Total {} riders en {} paginas",
                tenantId, cityId, allLocations.size(), page);

            return allLocations;

        } catch (Exception e) {
            logger.error("Tenant {}, Ciudad {}: Error obteniendo ubicaciones: {}",
                tenantId, cityId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Obtiene ubicaciones actuales agrupadas por ciudad para un tenant.
     *
     * @param tenantId Tenant ID
     * @return Map donde la key es cityId y value es la lista de riders
     */
    public Map<Integer, List<RiderLocationDto>> getCurrentRiderLocationsByCity(Long tenantId) {
        logger.info("Tenant {}: Obteniendo ubicaciones actuales agrupadas por ciudad", tenantId);

        Map<Integer, List<RiderLocationDto>> locationsByCity = new HashMap<>();

        // Obtener ciudades activas desde configuración del tenant
        List<Integer> activeCityIds = tenantSettingsService.getActiveCityIds(tenantId);

        for (Integer cityId : activeCityIds) {
            try {
                List<RiderLocationDto> locations = getRiderLocationsByCity(tenantId, cityId);
                if (!locations.isEmpty()) {
                    locationsByCity.put(cityId, locations);
                }
            } catch (Exception e) {
                logger.error("Tenant {}, Ciudad {}: Error: {}",
                    tenantId, cityId, e.getMessage());
            }
        }

        int totalRiders = locationsByCity.values().stream()
            .mapToInt(List::size)
            .sum();

        logger.info("Tenant {}: {} riders en {} ciudades",
            tenantId, totalRiders, locationsByCity.size());

        return locationsByCity;
    }

    // ===============================================
    // MÉTODOS PRIVADOS - PROCESAMIENTO DE DATOS
    // ===============================================

    /**
     * Extrae ubicaciones desde los datos de riders de la API.
     *
     * Procesa la respuesta de la API y convierte cada rider a RiderLocationDto,
     * filtrando aquellos sin coordenadas válidas.
     *
     * @param ridersData Datos crudos de riders desde la API
     * @return Lista de ubicaciones procesadas
     */
    private List<RiderLocationDto> extractLocationsFromRidersData(Object ridersData) {
        if (!(ridersData instanceof Map)) {
            logger.info("❌ ridersData no es Map");
            return new ArrayList<>();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> responseMap = (Map<String, Object>) ridersData;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ridersList = (List<Map<String, Object>>) responseMap.get("content");

        logger.debug("Riders desde API (content): {}", ridersList != null ? ridersList.size() : "null");

        if (ridersList == null || ridersList.isEmpty()) {
            return new ArrayList<>();
        }

        List<RiderLocationDto> converted = ridersList.stream()
            .map(this::convertToRiderLocationDto)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        logger.debug("✅ Procesados {} riders (incluyendo riders sin coordenadas)", converted.size());

        // Contar riders con y sin coordenadas para logging
        long withCoordinates = converted.stream()
            .filter(this::hasValidCoordinates)
            .count();
        long withoutCoordinates = converted.size() - withCoordinates;

        if (withoutCoordinates > 0) {
            logger.debug("📊 Estadísticas: {} con coordenadas, {} sin coordenadas",
                withCoordinates, withoutCoordinates);
        }

        return converted; // ✅ Retornar TODOS los riders, incluso sin coordenadas
    }

    /**
     * Convierte un Map de rider de la API a RiderLocationDto.
     */
    private RiderLocationDto convertToRiderLocationDto(Map<String, Object> riderMap) {
        try {
            RiderLocationDto dto = new RiderLocationDto();

            // Datos básicos
            Object employeeIdObj = riderMap.get("employee_id");
            dto.setEmployeeId(employeeIdObj != null ? employeeIdObj.toString() : null);
            dto.setFullName((String) riderMap.get("full_name"));
            dto.setStatus((String) riderMap.get("status"));

            // Location data
            @SuppressWarnings("unchecked")
            Map<String, Object> currentLocation = (Map<String, Object>) riderMap.get("current_location");

            if (currentLocation != null) {
                dto.setLatitude(getDoubleValue(currentLocation, "latitude"));
                dto.setLongitude(getDoubleValue(currentLocation, "longitude"));
                dto.setAccuracy(getDoubleValue(currentLocation, "accuracy"));
                dto.setUpdatedAt((String) currentLocation.get("updated_at"));
            }

            // Vehicle data
            @SuppressWarnings("unchecked")
            Map<String, Object> vehicleType = (Map<String, Object>) riderMap.get("vehicle_type");

            if (vehicleType != null) {
                dto.setVehicleTypeId(getIntegerValue(vehicleType, "id"));
                dto.setVehicleTypeName((String) vehicleType.get("name"));
            }

            // Delivery data - CORREGIDO: La API v1 usa deliveries_info.has_active_deliveries
            @SuppressWarnings("unchecked")
            Map<String, Object> deliveriesInfo = (Map<String, Object>) riderMap.get("deliveries_info");
            if (deliveriesInfo != null) {
                Boolean hasActiveDeliveries = (Boolean) deliveriesInfo.get("has_active_deliveries");
                dto.setHasActiveDelivery(hasActiveDeliveries != null && hasActiveDeliveries);
            } else {
                dto.setHasActiveDelivery(false);
            }

            return dto;

        } catch (Exception e) {
            logger.error("Error convirtiendo rider data: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Verifica si una ubicación tiene coordenadas válidas.
     */
    private boolean hasValidCoordinates(RiderLocationDto dto) {
        return dto.getLatitude() != null &&
               dto.getLongitude() != null &&
               dto.getLatitude() != 0.0 &&
               dto.getLongitude() != 0.0;
    }

    /**
     * Helper para extraer Double de Map de forma segura.
     */
    private Double getDoubleValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return null;
    }

    /**
     * Helper para extraer Integer de Map de forma segura.
     */
    private Integer getIntegerValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    // ===============================================
    // MÉTODOS DE TESTING
    // ===============================================

    /**
     * Envía datos ficticios para testing cuando mock-data está habilitado.
     */
    private void sendMockDataForTesting() {
        logger.info("Enviando datos mock para testing...");

        try {
            // Obtener primer tenant activo
            List<TenantEntity> tenants = tenantRepository.findByIsActive(true);
            if (tenants.isEmpty()) {
                logger.warn("No hay tenants activos para enviar datos mock");
                return;
            }

            Long tenantId = tenants.get(0).getId();
            List<Integer> activeCityIds = tenantSettingsService.getActiveCityIds(tenantId);

            for (Integer cityId : activeCityIds) {
                List<RiderLocationDto> mockLocations = generateMockLocations(tenantId, cityId);
                webSocketHandler.broadcastRiderLocationsByCity(tenantId, cityId, mockLocations);
                logger.info("Tenant {}: Enviadas {} ubicaciones mock para ciudad {}", tenantId, mockLocations.size(), cityId);
            }

        } catch (Exception e) {
            logger.error("Error enviando datos mock: {}", e.getMessage(), e);
        }
    }

    /**
     * Genera ubicaciones ficticias para testing.
     */
    private List<RiderLocationDto> generateMockLocations(Long tenantId, Integer cityId) {
        List<RiderLocationDto> mockLocations = new ArrayList<>();
        Random random = new Random();

        // Coordenadas base por ciudad (Madrid como ejemplo)
        double baseLatitude = 40.4168;
        double baseLongitude = -3.7038;

        // Generar entre 5 y 15 riders mock
        int numRiders = 5 + random.nextInt(11);

        for (int i = 0; i < numRiders; i++) {
            RiderLocationDto dto = new RiderLocationDto();
            dto.setEmployeeId("MOCK_T" + tenantId + "_C" + cityId + "_" + i);
            dto.setFullName("Rider Mock " + i);
            dto.setStatus(getRandomStatus(random));
            dto.setLatitude(baseLatitude + (random.nextDouble() - 0.5) * 0.1);
            dto.setLongitude(baseLongitude + (random.nextDouble() - 0.5) * 0.1);
            dto.setAccuracy(10.0 + random.nextDouble() * 20.0);
            dto.setUpdatedAt(LocalDateTime.now().toString());
            dto.setVehicleTypeId(random.nextInt(4) + 1);
            dto.setVehicleTypeName("Vehicle " + dto.getVehicleTypeId());
            dto.setHasActiveDelivery(random.nextBoolean());

            mockLocations.add(dto);
        }

        return mockLocations;
    }

    /**
     * Genera un estado aleatorio para riders mock.
     */
    private String getRandomStatus(Random random) {
        String[] statuses = {"WORKING", "READY", "AVAILABLE", "BREAK", "NOT_WORKING"};
        return statuses[random.nextInt(statuses.length)];
    }
}
