package es.hargos.ritrack.service;

import es.hargos.ritrack.client.GlovoClient;
import es.hargos.ritrack.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class RiderFilterService {

    private static final Logger logger = LoggerFactory.getLogger(RiderFilterService.class);

    // Thread pool COMPARTIDO para todos los usuarios
    private static final int CORE_POOL_SIZE = 20;
    private static final int MAX_POOL_SIZE = 50;
    private static final ExecutorService SHARED_EXECUTOR = new ThreadPoolExecutor(
            CORE_POOL_SIZE,
            MAX_POOL_SIZE,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r);
                    thread.setName("RiderSearch-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // Si está lleno, ejecuta en el thread del llamador
    );

    // Control de concurrencia para API
    private static final Semaphore API_SEMAPHORE = new Semaphore(20); // Max 20 llamadas API simultáneas

    // Pool compartido para procesamiento de Rooster (reutilizado en lugar de crear uno nuevo cada vez)
    private static final java.util.concurrent.ForkJoinPool ROOSTER_POOL = new java.util.concurrent.ForkJoinPool(
        10,
        java.util.concurrent.ForkJoinPool.defaultForkJoinWorkerThreadFactory,
        null,
        false
    );

    // Métricas de rendimiento
    private final AtomicInteger activeSearches = new AtomicInteger(0);
    private final AtomicLong totalSearchTime = new AtomicLong(0);
    private final AtomicInteger totalSearches = new AtomicInteger(0);
    private final AtomicInteger cacheHits = new AtomicInteger(0);
    private final AtomicInteger cacheMisses = new AtomicInteger(0);

    private final GlovoClient glovoClient;
    private final RoosterCacheService roosterCache;
    private final TenantSettingsService tenantSettingsService;
    private final UserCityService userCityService;

    // Caché temporal para Live API
    private final Map<String, CachedLiveData> liveCache = new ConcurrentHashMap<>();
    private final long LIVE_CACHE_TTL;

    // Timeouts configurables
    private final long SEARCH_TIMEOUT_SECONDS;
    private final long CITY_TIMEOUT_SECONDS;

    @Autowired
    public RiderFilterService(
            GlovoClient glovoClient,
            RoosterCacheService roosterCache,
            TenantSettingsService tenantSettingsService,
            UserCityService userCityService,
            @Value("${cache.live.city.ttl-seconds:30}") long liveTtlSeconds,
            @Value("${api.search-timeout-seconds:15}") long searchTimeoutSeconds,
            @Value("${api.city-timeout-seconds:3}") long cityTimeoutSeconds) {

        this.glovoClient = glovoClient;
        this.roosterCache = roosterCache;
        this.tenantSettingsService = tenantSettingsService;
        this.userCityService = userCityService;
        this.LIVE_CACHE_TTL = liveTtlSeconds * 1000;
        this.SEARCH_TIMEOUT_SECONDS = searchTimeoutSeconds;
        this.CITY_TIMEOUT_SECONDS = cityTimeoutSeconds;

        logger.info("Servicio iniciado - Pool: {}-{} threads, API limit: 20, Timeouts: {}s/{}s",
                CORE_POOL_SIZE, MAX_POOL_SIZE, searchTimeoutSeconds, cityTimeoutSeconds);
    }

    /**
     * Método principal de búsqueda con control de concurrencia
     * Aplicar filtro automático de ciudades si el usuario tiene asignaciones
     */
    public PaginatedResponseDto<RiderSummaryDto> searchRiders(Long tenantId, Long userId, RiderFilterDto filters) {
        int currentActive = activeSearches.incrementAndGet();
        long startTime = System.currentTimeMillis();

        // Log de concurrencia
        if (currentActive > 10) {
            logger.warn("⚠️ Alta concurrencia: {} búsquedas activas", currentActive);
        }

        // NUEVO: Aplicar filtro automático de ciudades por usuario
        List<Long> userCityIds = userCityService.getUserCityIds(userId);
        if (userCityIds != null && !userCityIds.isEmpty()) {
            logger.info("🔒 Usuario ID {} tiene {} ciudades asignadas - Aplicando filtro automático: {}",
                    userId, userCityIds.size(), userCityIds);

            // Si el usuario ya tiene un filtro de ciudad específico, verificar que esté en sus ciudades asignadas
            if (filters.getCityId() != null) {
                if (!userCityIds.contains(filters.getCityId().longValue())) {
                    logger.warn("⚠️ Usuario ID {} intentó filtrar por ciudad {} que no tiene asignada. Aplicando restricción.",
                            userId, filters.getCityId());
                    // El usuario no puede ver esa ciudad, forzar a sus ciudades asignadas
                    filters.setCityId(null);
                }
            }
            // Nota: El filtro de ciudades se aplica más abajo en getLiveRiders() y getRoosterRiders()
        } else {
            logger.debug("Usuario ID {} no tiene restricciones de ciudades - Puede ver todas", userId);
        }

        logger.info("🔍 Tenant {}, User {} [Thread {}] Iniciando búsqueda - Filtros: {}",
                tenantId,
                userId,
                Thread.currentThread().getId(),
                filters.hasFilters() ? filters.getFilterDescription() : "SIN FILTROS");

        try {
            // Verificar si el pool está saturado
            ThreadPoolExecutor executor = (ThreadPoolExecutor) SHARED_EXECUTOR;
            if (executor.getQueue().size() > 100) {
                logger.warn("⚠️ Cola de ejecución alta: {} tareas pendientes", executor.getQueue().size());
            }

            // Ejecutar Live y Rooster EN PARALELO con timeout
            // NUEVO: Pasar userCityIds para aplicar filtro de ciudades
            CompletableFuture<List<RiderSummaryDto>> liveFuture = CompletableFuture
                    .supplyAsync(() -> getLiveRiders(tenantId, filters, userCityIds), SHARED_EXECUTOR)
                    .orTimeout(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            CompletableFuture<List<RiderSummaryDto>> roosterFuture = CompletableFuture
                    .supplyAsync(() -> getRoosterRiders(tenantId, filters, userCityIds), SHARED_EXECUTOR)
                    .orTimeout(SEARCH_TIMEOUT_SECONDS - 2, TimeUnit.SECONDS);

            // Esperar ambos resultados
            List<RiderSummaryDto> liveRiders = liveFuture.join();
            List<RiderSummaryDto> roosterRiders = roosterFuture.join();

            // Combinar y eliminar duplicados
            Set<Integer> liveIds = liveRiders.stream()
                    .map(RiderSummaryDto::getRiderId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            List<RiderSummaryDto> allRiders = new ArrayList<>(liveRiders);

            // Agregar solo riders de Rooster que no están en Live
            long roosterUnique = roosterRiders.stream()
                    .filter(r -> !liveIds.contains(r.getRiderId()))
                    .peek(allRiders::add)
                    .count();

            // Ordenar eficientemente
            PaginatedResponseDto<RiderSummaryDto> result = optimizedPagination(allRiders, filters);

            // Métricas
            long duration = System.currentTimeMillis() - startTime;
            totalSearchTime.addAndGet(duration);
            totalSearches.incrementAndGet();

            logger.info("✅ Tenant {} [Usuario {}] Búsqueda completada en {}ms | Live: {} | Rooster únicos: {} | Total: {} | Cache hit rate: {}%",
                    tenantId,
                    Thread.currentThread().getId(),
                    duration, liveRiders.size(), roosterUnique,
                    result.getTotalElements(),
                    calculateHitRate());

            // Log de métricas cada 50 búsquedas
            if (totalSearches.get() % 50 == 0) {
                logPerformanceMetrics();
            }

            return result;

        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                logger.error("❌ Timeout en búsqueda después de {}s", SEARCH_TIMEOUT_SECONDS);
                return new PaginatedResponseDto<>(List.of(), filters.getPage(), 10, 0L);
            }
            throw e;
        } catch (Exception e) {
            logger.error("❌ Error en búsqueda: {}", e.getMessage(), e);
            return new PaginatedResponseDto<>(List.of(), filters.getPage(), 10, 0L);
        } finally {
            activeSearches.decrementAndGet();
        }
    }

    /**
     * Obtiene riders de Live API con caché y control de concurrencia
     * NUEVO: Aplica filtro de ciudades del usuario si están asignadas
     */
    private List<RiderSummaryDto> getLiveRiders(Long tenantId, RiderFilterDto filters, List<Long> userCityIds) {
        long startTime = System.currentTimeMillis();
        List<RiderSummaryDto> results = Collections.synchronizedList(new ArrayList<>());

        try {
            List<Integer> cityIds;

            // NUEVO: Aplicar filtro de ciudades del usuario
            if (userCityIds != null && !userCityIds.isEmpty()) {
                // Usuario tiene restricción de ciudades
                cityIds = userCityIds.stream()
                        .map(Long::intValue)
                        .collect(Collectors.toList());
                logger.debug("Filtrando Live API por ciudades del usuario: {}", cityIds);
            } else if (filters.getCityId() != null) {
                // Usuario sin restricción + filtro manual de ciudad
                cityIds = List.of(filters.getCityId());
            } else {
                // Intentar usar caché completo
                String allCitiesKey = "all-cities-data";
                CachedLiveData allCitiesCache = liveCache.get(allCitiesKey);

                if (allCitiesCache != null && !allCitiesCache.isExpired()) {
                    cacheHits.incrementAndGet();
                    logger.debug("💾 CACHE HIT TOTAL: Todas las ciudades desde caché");

                    @SuppressWarnings("unchecked")
                    Map<Integer, List<Map<String, Object>>> allData =
                            (Map<Integer, List<Map<String, Object>>>) allCitiesCache.data;

                    // Procesar en paralelo desde caché
                    allData.entrySet().parallelStream().forEach(entry -> {
                        Integer cityId = entry.getKey();
                        entry.getValue().forEach(riderData -> {
                            RiderSummaryDto rider = createRiderFromLiveData(tenantId, riderData, cityId);
                            if (rider != null && matchesLiveFilters(rider, filters)) {
                                results.add(rider);
                            }
                        });
                    });

                    long duration = System.currentTimeMillis() - startTime;
                    logger.debug("Live desde CACHE: {} riders en {}ms", results.size(), duration);
                    return results;
                }

                cacheMisses.incrementAndGet();
                cityIds = tenantSettingsService.getActiveCityIds(tenantId);
            }

            // Procesar ciudades con límite de concurrencia
            List<CompletableFuture<List<RiderSummaryDto>>> futures = new ArrayList<>();

            for (Integer cityId : cityIds) {
                CompletableFuture<List<RiderSummaryDto>> future = CompletableFuture
                        .supplyAsync(() -> processCityWithRateLimit(tenantId, cityId, filters), SHARED_EXECUTOR)
                        .orTimeout(CITY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                futures.add(future);
            }

            // Esperar resultados con manejo de errores
            for (CompletableFuture<List<RiderSummaryDto>> future : futures) {
                try {
                    results.addAll(future.join());
                } catch (CompletionException e) {
                    if (e.getCause() instanceof TimeoutException) {
                        logger.warn("⏱️ Timeout procesando ciudad");
                    } else {
                        logger.debug("Error procesando ciudad: {}", e.getMessage());
                    }
                }
            }

            // Cachear resultado completo si es búsqueda general
            if (filters.getCityId() == null && !cityIds.isEmpty()) {
                cacheAllCitiesData(cityIds);
            }

            long duration = System.currentTimeMillis() - startTime;
            logger.debug("Live API procesado: {} riders en {}ms", results.size(), duration);

        } catch (Exception e) {
            logger.error("Error obteniendo riders de Live: {}", e.getMessage());
        }

        return results;
    }

    /**
     * Procesa una ciudad con control de rate limiting
     */
    private List<RiderSummaryDto> processCityWithRateLimit(Long tenantId, Integer cityId, RiderFilterDto filters) {
        try {
            // Adquirir permiso para llamar a la API
            boolean acquired = API_SEMAPHORE.tryAcquire(2, TimeUnit.SECONDS);
            if (!acquired) {
                logger.warn("⏳ Esperando permiso para procesar ciudad {}", cityId);
                API_SEMAPHORE.acquire(); // Esperar indefinidamente si es necesario
            }

            try {
                return processCity(tenantId, cityId, filters);
            } finally {
                API_SEMAPHORE.release();
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrumpido procesando ciudad {}", cityId);
            return new ArrayList<>();
        }
    }

    /**
     * Procesa una ciudad específica
     */
    private List<RiderSummaryDto> processCity(Long tenantId, Integer cityId, RiderFilterDto filters) {
        List<RiderSummaryDto> cityResults = new ArrayList<>();

        try {
            List<Map<String, Object>> cityRiders = getCachedCityRiders(tenantId, cityId);

            for (Map<String, Object> riderData : cityRiders) {
                RiderSummaryDto rider = createRiderFromLiveData(tenantId, riderData, cityId);
                if (rider != null && matchesLiveFilters(rider, filters)) {
                    cityResults.add(rider);
                }
            }

        } catch (Exception e) {
            logger.debug("Error procesando ciudad {}: {}", cityId, e.getMessage());
        }

        return cityResults;
    }

    /**
     * Obtiene riders de una ciudad con caché
     */
    private List<Map<String, Object>> getCachedCityRiders(Long tenantId, Integer cityId) throws Exception {
        String cacheKey = "tenant-" + tenantId + "-city-" + cityId;
        CachedLiveData cached = liveCache.get(cacheKey);

        if (cached != null && !cached.isExpired()) {
            cacheHits.incrementAndGet();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) cached.data;
            return data;
        }

        cacheMisses.incrementAndGet();
        List<Map<String, Object>> cityRiders = getAllRidersFromCity(tenantId, cityId);
        liveCache.put(cacheKey, new CachedLiveData(cityRiders));

        // Limpiar cachés expirados ocasionalmente
        if (Math.random() < 0.05) { // 5% de probabilidad
            cleanExpiredCaches();
        }

        return cityRiders;
    }

    // Throttling para respetar límite de Live API: 4 req/s (margen de seguridad)
    private static final int SAFE_REQ_PER_SECOND = 4;
    private static final long THROTTLE_DELAY_MS = 1000 / SAFE_REQ_PER_SECOND; // 250ms

    /**
     * Obtiene todos los riders de una ciudad desde Live API con throttling.
     *
     * THROTTLING: Respeta límite de 4 req/s (margen de seguridad del límite de 5 req/s de Glovo).
     * Aplica delay de 250ms entre páginas para garantizar que no se exceda el rate limit.
     */
    private List<Map<String, Object>> getAllRidersFromCity(Long tenantId, Integer cityId) throws Exception {
        List<Map<String, Object>> allRiders = new ArrayList<>();
        int page = 0;
        boolean hasMore = true;

        while (hasMore && page < 50) {
            long startTime = System.currentTimeMillis();

            Object response = glovoClient.getRidersByCity(tenantId, cityId, page, 100, "employee_id");

            if (response instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> responseMap = (Map<String, Object>) response;

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> content = (List<Map<String, Object>>) responseMap.get("content");

                if (content != null && !content.isEmpty()) {
                    allRiders.addAll(content);
                    Boolean isLast = (Boolean) responseMap.get("is_last");
                    hasMore = isLast != null && !isLast;
                    page++;

                    // THROTTLING: Esperar entre páginas para respetar límite de 4 req/s
                    if (hasMore && page < 50) {
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
                                hasMore = false;
                            }
                        }
                    }
                } else {
                    hasMore = false;
                }
            } else {
                hasMore = false;
            }
        }

        return allRiders;
    }

    /**
     * Obtiene riders de Rooster desde caché
     * NUEVO: Aplica filtro de ciudades del usuario si están asignadas
     */
    private List<RiderSummaryDto> getRoosterRiders(Long tenantId, RiderFilterDto filters, List<Long> userCityIds) {
        long startTime = System.currentTimeMillis();
        List<RiderSummaryDto> results = new ArrayList<>();

        try {
            List<?> allEmployees = roosterCache.getAllEmployees(tenantId);

            if (allEmployees == null || allEmployees.isEmpty()) {
                return results;
            }

            // NUEVO: Convertir userCityIds a Integer para comparación
            List<Integer> userCityIdsInt = null;
            if (userCityIds != null && !userCityIds.isEmpty()) {
                userCityIdsInt = userCityIds.stream()
                        .map(Long::intValue)
                        .collect(Collectors.toList());
                logger.debug("Filtrando Rooster por ciudades del usuario: {}", userCityIdsInt);
            }
            final List<Integer> finalUserCityIds = userCityIdsInt;

            // Usar parallel stream con pool compartido (reutilizado en lugar de crear uno nuevo)
            try {
                results = ROOSTER_POOL.submit(() ->
                        allEmployees.parallelStream()
                                .map(emp -> {
                                    try {
                                        RiderSummaryDto rider = RiderSummaryDto.fromRoosterApiData(emp);
                                        if (rider != null) {
                                            rider.setCityId(extractCityFromEmployee(emp));
                                        }
                                        return rider;
                                    } catch (Exception e) {
                                        return null;
                                    }
                                })
                                .filter(Objects::nonNull)
                                .filter(rider -> matchesRoosterFilters(rider, filters))
                                .filter(rider -> {
                                    // Aplicar filtro de ciudades del usuario
                                    if (finalUserCityIds != null && !finalUserCityIds.isEmpty()) {
                                        return rider.getCityId() != null && finalUserCityIds.contains(rider.getCityId());
                                    }
                                    return true; // Sin restricción de ciudades
                                })
                                .collect(Collectors.toList())
                ).get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                logger.warn("Timeout procesando Rooster data para tenant {}", tenantId);
                results = new ArrayList<>();
            } catch (java.util.concurrent.ExecutionException e) {
                logger.error("Error procesando Rooster: {}", e.getMessage());
                results = new ArrayList<>();
            }

            long duration = System.currentTimeMillis() - startTime;
            logger.debug("Rooster: {} riders filtrados de {} totales en {}ms",
                    results.size(), allEmployees.size(), duration);

        } catch (Exception e) {
            logger.error("Error obteniendo riders de Rooster: {}", e.getMessage());
        }

        return results;
    }

    /**
     * Paginación optimizada - solo ordena lo necesario
     */
    private PaginatedResponseDto<RiderSummaryDto> optimizedPagination(
            List<RiderSummaryDto> allRiders,
            RiderFilterDto filters) {

        int page = filters.getPage();
        int size = 10;

        // Si hay pocos resultados, ordenar todo
        if (allRiders.size() <= 100) {
            allRiders.sort(this::compareRiders);
            return PaginatedResponseDto.of(allRiders, page, size);
        }

        // Para muchos resultados, usar ordenamiento parcial
        int start = page * size;
        int end = Math.min(start + size * 3, allRiders.size()); // Ordenar 3 páginas adelante

        // Ordenar solo la porción necesaria
        List<RiderSummaryDto> sorted = allRiders.stream()
                .sorted(this::compareRiders)
                .skip(start)
                .limit(size)
                .collect(Collectors.toList());

        return new PaginatedResponseDto<>(
                sorted, page, size, (long) allRiders.size()
        );
    }

    /**
     * Crea rider desde datos de Live
     */
    private RiderSummaryDto createRiderFromLiveData(Long tenantId, Map<String, Object> riderData, Integer cityId) {
        Integer employeeId = (Integer) riderData.get("employee_id");
        Object employeeData = findEmployeeInCache(tenantId, employeeId);

        RiderSummaryDto rider = RiderSummaryDto.fromLiveApiData(riderData, employeeData);
        if (rider != null) {
            rider.setCityId(cityId);
        }
        return rider;
    }

    /**
     * Busca empleado en caché de Rooster
     */
    private Object findEmployeeInCache(Long tenantId, Integer employeeId) {
        if (employeeId == null) return null;

        try {
            List<?> allEmployees = roosterCache.getAllEmployees(tenantId);
            if (allEmployees == null) return null;

            return allEmployees.stream()
                    .filter(emp -> {
                        if (emp instanceof Map) {
                            Integer id = (Integer) ((Map<?, ?>) emp).get("id");
                            return employeeId.equals(id);
                        }
                        return false;
                    })
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Cachea datos de todas las ciudades
     */
    private void cacheAllCitiesData(List<Integer> cityIds) {
        try {
            Map<Integer, List<Map<String, Object>>> allData = new ConcurrentHashMap<>();

            for (Integer cityId : cityIds) {
                CachedLiveData cityCache = liveCache.get("city-" + cityId);
                if (cityCache != null && !cityCache.isExpired()) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> data = (List<Map<String, Object>>) cityCache.data;
                    allData.put(cityId, data);
                }
            }

            if (!allData.isEmpty()) {
                liveCache.put("all-cities-data", new CachedLiveData(allData));
            }
        } catch (Exception e) {
            logger.debug("Error cacheando datos completos: {}", e.getMessage());
        }
    }

    /**
     * Limpia cachés expirados
     */
    private void cleanExpiredCaches() {
        int removed = 0;
        Iterator<Map.Entry<String, CachedLiveData>> iterator = liveCache.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().isExpired()) {
                iterator.remove();
                removed++;
            }
        }
        if (removed > 0) {
            logger.debug("Limpiados {} cachés expirados", removed);
        }
    }

    /**
     * Calcula hit rate del caché
     */
    private int calculateHitRate() {
        int total = cacheHits.get() + cacheMisses.get();
        if (total == 0) return 0;
        return (cacheHits.get() * 100) / total;
    }

    /**
     * Log de métricas de rendimiento
     */
    private void logPerformanceMetrics() {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) SHARED_EXECUTOR;

        logger.info("📊 MÉTRICAS DE RENDIMIENTO:");
        logger.info("  - Búsquedas totales: {}", totalSearches.get());
        logger.info("  - Tiempo promedio: {}ms",
                totalSearches.get() > 0 ? totalSearchTime.get() / totalSearches.get() : 0);
        logger.info("  - Cache hit rate: {}%", calculateHitRate());
        logger.info("  - Búsquedas activas: {}", activeSearches.get());
        logger.info("  - Threads activos: {}/{}", executor.getActiveCount(), executor.getPoolSize());
        logger.info("  - Cola de tareas: {}", executor.getQueue().size());
        logger.info("  - Permisos API disponibles: {}/20", API_SEMAPHORE.availablePermits());
    }

    // Métodos de filtrado...
    private boolean matchesLiveFilters(RiderSummaryDto rider, RiderFilterDto filters) {
        if (!filters.hasFilters()) return true;

        if (filters.getCityId() != null && !filters.getCityId().equals(rider.getCityId())) {
            return false;
        }

        if (filters.getRiderId() != null && !filters.getRiderId().equals(rider.getRiderId())) {
            return false;
        }

        if (filters.getName() != null && rider.getName() != null &&
                !rider.getName().toLowerCase().contains(filters.getName().toLowerCase())) {
            return false;
        }

        if (filters.getPhone() != null) {
            // Si se busca por teléfono, solo incluir riders que tengan teléfono y coincida
            if (rider.getPhone() == null || !rider.getPhone().contains(filters.getPhone())) {
                return false;
            }
        }

        if (filters.getEmail() != null && rider.getEmail() != null &&
                !rider.getEmail().toLowerCase().contains(filters.getEmail().toLowerCase())) {
            return false;
        }

        if (filters.getStatus() != null && rider.getStatus() != null &&
                !rider.getStatus().equalsIgnoreCase(filters.getStatus())) {
            return false;
        }

        if (filters.getContractType() != null && rider.getContractType() != null &&
                !rider.getContractType().equals(filters.getContractType().toUpperCase())) {
            return false;
        }

        if (filters.getIsWorking() != null && filters.getIsWorking() != rider.isActive()) {
            return false;
        }

        if (filters.getHasActiveDelivery() != null) {
            boolean hasDelivery = rider.getDeliveredOrders() != null && rider.getDeliveredOrders() > 0;
            if (filters.getHasActiveDelivery() != hasDelivery) {
                return false;
            }
        }

        return true;
    }

    private boolean matchesRoosterFilters(RiderSummaryDto rider, RiderFilterDto filters) {
        if (!filters.hasFilters()) return true;

        if (filters.getIsWorking() != null && filters.getIsWorking()) {
            return false;
        }

        if (filters.getHasActiveDelivery() != null && filters.getHasActiveDelivery()) {
            return false;
        }

        if (filters.getStatus() != null && !"not_working".equalsIgnoreCase(filters.getStatus())) {
            return false;
        }

        if (filters.getCityId() != null && !filters.getCityId().equals(rider.getCityId())) {
            return false;
        }

        if (filters.getRiderId() != null && !filters.getRiderId().equals(rider.getRiderId())) {
            return false;
        }

        if (filters.getName() != null && rider.getName() != null &&
                !rider.getName().toLowerCase().contains(filters.getName().toLowerCase())) {
            return false;
        }

        if (filters.getPhone() != null) {
            // Si se busca por teléfono, solo incluir riders que tengan teléfono y coincida
            if (rider.getPhone() == null || !rider.getPhone().contains(filters.getPhone())) {
                return false;
            }
        }

        if (filters.getEmail() != null && rider.getEmail() != null &&
                !rider.getEmail().toLowerCase().contains(filters.getEmail().toLowerCase())) {
            return false;
        }

        if (filters.getContractType() != null && rider.getContractType() != null &&
                !rider.getContractType().equals(filters.getContractType().toUpperCase())) {
            return false;
        }

        return true;
    }

    private Integer extractCityFromEmployee(Object employeeData) {
        if (!(employeeData instanceof Map)) return null;

        @SuppressWarnings("unchecked")
        Map<String, Object> empMap = (Map<String, Object>) employeeData;

        @SuppressWarnings("unchecked")
        Map<String, Object> activeContract = (Map<String, Object>) empMap.get("active_contract");
        if (activeContract != null) {
            Integer cityId = (Integer) activeContract.get("city_id");
            if (cityId != null) return cityId;
        }

        return (Integer) empMap.get("city_id");
    }

    private int compareRiders(RiderSummaryDto a, RiderSummaryDto b) {
        if (a.isActive() != b.isActive()) {
            return a.isActive() ? -1 : 1;
        }

        if (a.getName() != null && b.getName() != null) {
            return a.getName().compareToIgnoreCase(b.getName());
        }

        return Integer.compare(
                a.getRiderId() != null ? a.getRiderId() : 0,
                b.getRiderId() != null ? b.getRiderId() : 0
        );
    }

    /**
     * Obtiene métricas del servicio
     */
    public Map<String, Object> getMetrics() {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) SHARED_EXECUTOR;

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("total_searches", totalSearches.get());
        metrics.put("average_search_time_ms",
                totalSearches.get() > 0 ? totalSearchTime.get() / totalSearches.get() : 0);
        metrics.put("cache_hit_rate", calculateHitRate());
        metrics.put("active_searches", activeSearches.get());
        metrics.put("thread_pool_active", executor.getActiveCount());
        metrics.put("thread_pool_size", executor.getPoolSize());
        metrics.put("thread_pool_queue", executor.getQueue().size());
        metrics.put("api_permits_available", API_SEMAPHORE.availablePermits());
        metrics.put("live_cache_entries", liveCache.size());

        return metrics;
    }

    /**
     * Limpia el cache de un tenant específico
     * MULTI-TENANT: Solo invalida el cache de un tenant, no de todos
     */
    public void clearCache(Long tenantId) {
        // Limpiar cache de Rooster para este tenant
        roosterCache.clearCache(tenantId);

        // Limpiar entradas de Live cache que pertenecen a este tenant
        int removedEntries = 0;
        Iterator<Map.Entry<String, CachedLiveData>> iterator = liveCache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CachedLiveData> entry = iterator.next();
            if (entry.getKey().startsWith("tenant-" + tenantId + "-")) {
                iterator.remove();
                removedEntries++;
            }
        }

        logger.info("Tenant {}: Cache limpiado ({} entradas de Live cache removidas)", tenantId, removedEntries);
    }

    /**
     * Limpia TODOS los caches de TODOS los tenants (usar con precaución)
     * Solo para mantenimiento o emergencias
     */
    public void clearAllTenantsCache() {
        roosterCache.clearAllTenantsCache();
        liveCache.clear();
        cacheHits.set(0);
        cacheMisses.set(0);
        logger.warn("ADVERTENCIA: Todos los cachés y métricas limpiados para TODOS los tenants");
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Cerrando servicio de búsqueda...");
        SHARED_EXECUTOR.shutdown();
        ROOSTER_POOL.shutdown();
        try {
            if (!SHARED_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                SHARED_EXECUTOR.shutdownNow();
            }
            if (!ROOSTER_POOL.awaitTermination(5, TimeUnit.SECONDS)) {
                ROOSTER_POOL.shutdownNow();
            }
        } catch (InterruptedException e) {
            SHARED_EXECUTOR.shutdownNow();
            ROOSTER_POOL.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("Servicio de búsqueda cerrado correctamente");
    }

    // Clase interna para caché
    private class CachedLiveData {
        private final Object data;
        private final long timestamp;

        public CachedLiveData(Object data) {
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > LIVE_CACHE_TTL;
        }
    }
}