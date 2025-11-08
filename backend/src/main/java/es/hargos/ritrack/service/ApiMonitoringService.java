package es.hargos.ritrack.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Servicio para monitorear errores 429 (Rate Limit) y estadísticas de API.
 *
 * MULTI-TENANT: Cada tenant tiene sus propias estadísticas aisladas.
 *
 * Registra:
 * - Errores 429 por tenant, endpoint y tiempo
 * - Frecuencia de errores por minuto/hora
 * - Endpoints más problemáticos
 *
 * Acceso: Solo SUPER_ADMIN puede visualizar estas métricas
 */
@Service
public class ApiMonitoringService {

    private static final Logger logger = LoggerFactory.getLogger(ApiMonitoringService.class);
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Estructura: tenantId -> lista de eventos 429
    private final Map<Long, List<RateLimitEvent>> rateLimitEvents = new ConcurrentHashMap<>();

    // Contadores globales por tenant
    private final Map<Long, AtomicInteger> totalRateLimitsByTenant = new ConcurrentHashMap<>();

    // Máximo de eventos a mantener en memoria por tenant (para no llenar memoria)
    private static final int MAX_EVENTS_PER_TENANT = 1000;

    /**
     * Registra un error 429 recibido desde Glovo API
     *
     * @param tenantId Tenant que recibió el error
     * @param endpoint Endpoint que falló (ej: "GET /v2/external/rider/{id}")
     * @param service Servicio que hizo la llamada (ej: "RiderDetailService")
     */
    public void recordRateLimitError(Long tenantId, String endpoint, String service) {
        RateLimitEvent event = new RateLimitEvent(
            LocalDateTime.now(),
            endpoint,
            service
        );

        // Agregar evento a la lista del tenant
        rateLimitEvents.computeIfAbsent(tenantId, k -> Collections.synchronizedList(new ArrayList<>()))
                       .add(event);

        // Incrementar contador global
        totalRateLimitsByTenant.computeIfAbsent(tenantId, k -> new AtomicInteger(0))
                               .incrementAndGet();

        // Limpiar eventos antiguos si excede el límite
        List<RateLimitEvent> events = rateLimitEvents.get(tenantId);
        if (events.size() > MAX_EVENTS_PER_TENANT) {
            synchronized (events) {
                // Mantener solo los últimos 1000 eventos
                List<RateLimitEvent> recentEvents = events.stream()
                    .skip(Math.max(0, events.size() - MAX_EVENTS_PER_TENANT))
                    .collect(Collectors.toList());
                rateLimitEvents.put(tenantId, Collections.synchronizedList(new ArrayList<>(recentEvents)));
            }
        }

        logger.warn("Tenant {}: Rate limit 429 recibido en {} desde {}", tenantId, endpoint, service);
    }

    /**
     * Obtiene estadísticas completas de rate limiting para todos los tenants (resumen)
     *
     * @return Mapa con resumen por tenant
     */
    public Map<String, Object> getAllTenantsStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("timestamp", LocalDateTime.now().format(formatter));
        stats.put("tenants", getAllTenantsSummary());
        stats.put("totalEvents", rateLimitEvents.values().stream().mapToInt(List::size).sum());

        return stats;
    }

    /**
     * Obtiene estadísticas DETALLADAS de rate limiting para todos los tenants.
     * Incluye clasificación por endpoints y servicios para cada tenant.
     *
     * @return Mapa con estadísticas detalladas de todos los tenants
     */
    public Map<String, Object> getAllTenantsDetailedStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("timestamp", LocalDateTime.now().format(formatter));

        // Total global de eventos
        int totalEvents = rateLimitEvents.values().stream().mapToInt(List::size).sum();
        stats.put("totalEvents", totalEvents);

        // Total global de errores 429
        int totalErrors = totalRateLimitsByTenant.values().stream()
            .mapToInt(AtomicInteger::get)
            .sum();
        stats.put("totalRateLimitErrors", totalErrors);

        // Detalles por tenant
        List<Map<String, Object>> tenantsDetails = rateLimitEvents.entrySet().stream()
            .map(entry -> {
                Long tenantId = entry.getKey();
                List<RateLimitEvent> events = entry.getValue();

                Map<String, Object> tenantStats = new HashMap<>();
                tenantStats.put("tenantId", tenantId);
                tenantStats.put("totalErrors", totalRateLimitsByTenant.getOrDefault(tenantId, new AtomicInteger(0)).get());
                tenantStats.put("eventsInMemory", events.size());

                // Clasificación por endpoint
                Map<String, Long> byEndpoint = events.stream()
                    .collect(Collectors.groupingBy(e -> e.endpoint, Collectors.counting()));
                tenantStats.put("errorsByEndpoint", byEndpoint);

                // Clasificación por servicio
                Map<String, Long> byService = events.stream()
                    .collect(Collectors.groupingBy(e -> e.service, Collectors.counting()));
                tenantStats.put("errorsByService", byService);

                // Eventos en última hora
                LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
                long lastHour = events.stream()
                    .filter(e -> e.timestamp.isAfter(oneHourAgo))
                    .count();
                tenantStats.put("errorsLastHour", lastHour);

                // Eventos en últimos 10 minutos
                LocalDateTime tenMinutesAgo = LocalDateTime.now().minusMinutes(10);
                long last10Min = events.stream()
                    .filter(e -> e.timestamp.isAfter(tenMinutesAgo))
                    .count();
                tenantStats.put("errorsLast10Minutes", last10Min);

                return tenantStats;
            })
            .sorted((a, b) -> Long.compare((Long) b.get("errorsLastHour"), (Long) a.get("errorsLastHour")))
            .collect(Collectors.toList());

        stats.put("tenants", tenantsDetails);

        // Clasificación GLOBAL por endpoint (todos los tenants combinados)
        Map<String, Long> globalByEndpoint = rateLimitEvents.values().stream()
            .flatMap(List::stream)
            .collect(Collectors.groupingBy(e -> e.endpoint, Collectors.counting()));
        stats.put("globalErrorsByEndpoint", globalByEndpoint);

        // Clasificación GLOBAL por servicio (todos los tenants combinados)
        Map<String, Long> globalByService = rateLimitEvents.values().stream()
            .flatMap(List::stream)
            .collect(Collectors.groupingBy(e -> e.service, Collectors.counting()));
        stats.put("globalErrorsByService", globalByService);

        return stats;
    }

    /**
     * Obtiene estadísticas de rate limiting para un tenant específico
     *
     * @param tenantId ID del tenant
     * @return Estadísticas detalladas
     */
    public Map<String, Object> getTenantStatistics(Long tenantId) {
        List<RateLimitEvent> events = rateLimitEvents.getOrDefault(tenantId, new ArrayList<>());

        Map<String, Object> stats = new HashMap<>();
        stats.put("tenantId", tenantId);
        stats.put("timestamp", LocalDateTime.now().format(formatter));
        stats.put("totalRateLimitErrors", totalRateLimitsByTenant.getOrDefault(tenantId, new AtomicInteger(0)).get());
        stats.put("eventsInMemory", events.size());

        // Eventos por endpoint
        Map<String, Long> byEndpoint = events.stream()
            .collect(Collectors.groupingBy(e -> e.endpoint, Collectors.counting()));
        stats.put("errorsByEndpoint", byEndpoint);

        // Eventos por servicio
        Map<String, Long> byService = events.stream()
            .collect(Collectors.groupingBy(e -> e.service, Collectors.counting()));
        stats.put("errorsByService", byService);

        // Eventos en última hora
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        long lastHour = events.stream()
            .filter(e -> e.timestamp.isAfter(oneHourAgo))
            .count();
        stats.put("errorsLastHour", lastHour);

        // Eventos en últimos 10 minutos
        LocalDateTime tenMinutesAgo = LocalDateTime.now().minusMinutes(10);
        long last10Min = events.stream()
            .filter(e -> e.timestamp.isAfter(tenMinutesAgo))
            .count();
        stats.put("errorsLast10Minutes", last10Min);

        // Últimos 20 eventos (más recientes)
        List<Map<String, String>> recentEvents = events.stream()
            .sorted(Comparator.comparing(e -> e.timestamp, Comparator.reverseOrder()))
            .limit(20)
            .map(e -> {
                Map<String, String> eventMap = new HashMap<>();
                eventMap.put("timestamp", e.timestamp.format(formatter));
                eventMap.put("endpoint", e.endpoint);
                eventMap.put("service", e.service);
                return eventMap;
            })
            .collect(Collectors.toList());
        stats.put("recentEvents", recentEvents);

        return stats;
    }

    /**
     * Obtiene resumen de todos los tenants
     */
    private List<Map<String, Object>> getAllTenantsSummary() {
        return rateLimitEvents.entrySet().stream()
            .map(entry -> {
                Long tenantId = entry.getKey();
                List<RateLimitEvent> events = entry.getValue();

                Map<String, Object> summary = new HashMap<>();
                summary.put("tenantId", tenantId);
                summary.put("totalErrors", totalRateLimitsByTenant.getOrDefault(tenantId, new AtomicInteger(0)).get());
                summary.put("eventsInMemory", events.size());

                // Eventos en última hora
                LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
                long lastHour = events.stream()
                    .filter(e -> e.timestamp.isAfter(oneHourAgo))
                    .count();
                summary.put("errorsLastHour", lastHour);

                return summary;
            })
            .sorted((a, b) -> Long.compare((Long) b.get("errorsLastHour"), (Long) a.get("errorsLastHour")))
            .collect(Collectors.toList());
    }

    /**
     * Limpia todos los eventos registrados (útil para testing o reset)
     */
    public void clearAllEvents() {
        rateLimitEvents.clear();
        totalRateLimitsByTenant.clear();
        logger.info("Todas las estadísticas de rate limiting han sido limpiadas");
    }

    /**
     * Limpia eventos de un tenant específico
     */
    public void clearTenantEvents(Long tenantId) {
        rateLimitEvents.remove(tenantId);
        totalRateLimitsByTenant.remove(tenantId);
        logger.info("Tenant {}: Estadísticas de rate limiting limpiadas", tenantId);
    }

    /**
     * Clase interna para representar un evento de rate limit
     */
    private static class RateLimitEvent {
        final LocalDateTime timestamp;
        final String endpoint;
        final String service;

        RateLimitEvent(LocalDateTime timestamp, String endpoint, String service) {
            this.timestamp = timestamp;
            this.endpoint = endpoint;
            this.service = service;
        }
    }
}
