package es.hargos.ritrack.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.hargos.ritrack.dto.RiderLocationDto;
import es.hargos.ritrack.security.JwtUtil;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class RiderLocationWebSocketHandler implements WebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(RiderLocationWebSocketHandler.class);

    // MULTI-TENANT: Mapa que asocia cada sesión con su tenant autenticado
    // Key: sessionId, Value: tenantId
    private final ConcurrentHashMap<String, Long> sessionTenantMap = new ConcurrentHashMap<>();

    // Mapa que asocia cada sesion con la ciudad que está monitoreando
    // Key: sessionId, Value: cityId (0 significa todas las ciudades)
    private final ConcurrentHashMap<String, Integer> sessionCityMap = new ConcurrentHashMap<>();

    // Mapa que organiza las sesiones por ciudad para broadcasting eficiente
    // Key: cityId, Value: Set de sesiones (cityId null = todas las ciudades)
    private final ConcurrentHashMap<Integer, CopyOnWriteArraySet<WebSocketSession>> citySessions = new ConcurrentHashMap<>();

    // Set para sesiones que quieren ver todas las ciudades
    private final CopyOnWriteArraySet<WebSocketSession> allCitiesSessions = new CopyOnWriteArraySet<>();

    // ObjectMapper para serializar JSON
    private final ObjectMapper objectMapper;

    // JwtUtil para validar tokens
    private final JwtUtil jwtUtil;

    // Constructor que inyecta dependencias
    public RiderLocationWebSocketHandler(ObjectMapper objectMapper, JwtUtil jwtUtil) {
        this.objectMapper = objectMapper;
        this.jwtUtil = jwtUtil;
    }

    // Executor para manejar tareas en background
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    //Inicia la comunicacion y mantiene la sesion
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        logger.info("Nueva conexion WebSocket establecida. ID: {}", session.getId());

        // Enviar mensaje de bienvenida
        //sendMessageToSession(session, createWelcomeMessage());

        // Programar ping cada 30 segundos para mantener la conexión activa
        scheduler.scheduleAtFixedRate(() -> {
            if (session.isOpen()) {
                try {
                    session.sendMessage(new PingMessage());
                    //logger.info("Ping enviado");
                } catch (IOException e) {
                    logger.warn("Error enviando ping nativo a sesion {}: {}", session.getId(), e.getMessage());
                    removeSessionFromAllSubscriptions(session);
                }
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    //Manejo de los mensajes entrantes y sus respuestas
    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {

        if (!(message instanceof TextMessage)) {
            //logger.info("Pong recibido");
            //logger.debug("Mensaje no-texto recibido de {}: {}", session.getId(), message.getClass().getSimpleName());
            return;
        }

        //logger.debug("Mensaje recibido de {}: {}", session.getId(), message.getPayload());

        try {
            String payload = message.getPayload().toString();
            Map<String, Object> messageData = objectMapper.readValue(payload, Map.class);

            String action = (String) messageData.get("action");

            switch (action != null ? action : "") {
                case "authenticate":
                    handleAuthentication(session, messageData);
                    break;
                case "subscribe_city":
                    handleCitySubscription(session, messageData);
                    break;
                case "subscribe_all":
                    handleAllCitiesSubscription(session);
                    break;
                case "unsubscribe":
                    handleUnsubscription(session);
                    break;
                case "ping":
                    sendMessageToSession(session, createSimpleMessage("pong", "Pong response"));
                    break;
                case "get_current_locations":
                    sendMessageToSession(session, createSimpleMessage("status", "Solicitando ubicaciones actuales..."));
                    break;
                default:
                    sendMessageToSession(session, createErrorMessage("Accion no reconocida: " + action));
            }
        } catch (Exception e) {
            logger.error("Error procesando mensaje de sesion {}: {}", session.getId(), e.getMessage());
            sendMessageToSession(session, createErrorMessage("Error procesando mensaje"));
        }
    }


    /**###############################################################################################################
     * ACCIONES DISPONIBLES
     * ###############################################################################################################
     */

    /**
     * MULTI-TENANT: Autentica la sesión con un JWT
     * Extrae el tenantId del token y lo guarda en sessionTenantMap
     */
    private void handleAuthentication(WebSocketSession session, Map<String, Object> messageData) {
        try {
            String token = (String) messageData.get("token");

            if (token == null || token.trim().isEmpty()) {
                sendMessageToSession(session, createErrorMessage("Token JWT requerido para autenticación"));
                session.close(CloseStatus.NOT_ACCEPTABLE);
                return;
            }

            // Validar el JWT y extraer claims
            Claims claims = jwtUtil.validateAndExtractClaims(token);

            // Extraer tenantId del JWT (viene en los claims del token de HargosAuth)
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tenants = (List<Map<String, Object>>) claims.get("tenants");

            if (tenants == null || tenants.isEmpty()) {
                sendMessageToSession(session, createErrorMessage("JWT no contiene información de tenants"));
                session.close(CloseStatus.NOT_ACCEPTABLE);
                return;
            }

            // Buscar el tenant de RiTrack
            Long tenantId = null;
            for (Map<String, Object> tenant : tenants) {
                if ("RiTrack".equals(tenant.get("appName"))) {
                    Object tenantIdObj = tenant.get("tenantId");
                    if (tenantIdObj instanceof Number) {
                        tenantId = ((Number) tenantIdObj).longValue();
                    } else if (tenantIdObj instanceof String) {
                        tenantId = Long.parseLong((String) tenantIdObj);
                    }
                    break;
                }
            }

            if (tenantId == null) {
                sendMessageToSession(session, createErrorMessage("Usuario no tiene acceso a RiTrack"));
                session.close(CloseStatus.NOT_ACCEPTABLE);
                return;
            }

            // Guardar tenantId en el mapa de sesiones
            sessionTenantMap.put(session.getId(), tenantId);

            logger.info("Session {} autenticada para tenant {}", session.getId(), tenantId);
            sendMessageToSession(session, createSimpleMessage("authenticated",
                "Autenticación exitosa para tenant " + tenantId));

        } catch (Exception e) {
            logger.error("Error en autenticación de sesión {}: {}", session.getId(), e.getMessage());
            sendMessageToSession(session, createErrorMessage("Autenticación fallida: " + e.getMessage()));
            try {
                session.close(CloseStatus.NOT_ACCEPTABLE);
            } catch (IOException ioException) {
                logger.error("Error cerrando sesión: {}", ioException.getMessage());
            }
        }
    }

    /**
     * Session vinculada a una sola ciudad
     */
    private void handleCitySubscription(WebSocketSession session, Map<String, Object> messageData) {
        try {
            Object cityIdObj = messageData.get("city_id");
            Integer cityId = null;

            if (cityIdObj instanceof Number) {
                cityId = ((Number) cityIdObj).intValue();
            } else if (cityIdObj instanceof String) {
                cityId = Integer.parseInt((String) cityIdObj);
            }

            if (cityId == null || cityId <= 0) {
                sendMessageToSession(session, createErrorMessage("city_id debe ser un numero válido mayor a 0"));
                return;
            }

            // Remover de suscripción anterior si existe
            removeSessionFromAllSubscriptions(session);

            // Agregar a la nueva suscripción
            sessionCityMap.put(session.getId(), cityId);
            citySessions.computeIfAbsent(cityId, k -> new CopyOnWriteArraySet<>()).add(session);

            //String confirmMessage = String.format("Suscrito a actualizaciones de la ciudad ID: %d", cityId);
            //sendMessageToSession(session, createSimpleMessage("subscription_confirmed", confirmMessage));

            logger.info("Sesion {} suscrita a ciudad {}", session.getId(), cityId);

        } catch (NumberFormatException e) {
            sendMessageToSession(session, createErrorMessage("city_id debe ser un número válido"));
        } catch (Exception e) {
            logger.error("Error en suscripción a ciudad: {}", e.getMessage());
            sendMessageToSession(session, createErrorMessage("Error procesando suscripción"));
        }
    }

    /**
     * Vincula la sesion a todas las ciudades
     */
    private void handleAllCitiesSubscription(WebSocketSession session) {
        try {
            // Remover de suscripción anterior si existe
            removeSessionFromAllSubscriptions(session);

            // Agregar a suscripción de todas las ciudades
            allCitiesSessions.add(session);
//            sendMessageToSession(session, createSimpleMessage("subscription_confirmed",
//                    "Suscrito a actualizaciones de todas las ciudades"));
            logger.info("Sesion {} suscrita a todas las ciudades", session.getId());

        } catch (Exception e) {
            logger.error("Error en suscripcion a todas las ciudades: {}", e.getMessage());
            sendMessageToSession(session, createErrorMessage("Error procesando suscripcion"));
        }
    }

    /**
     * Desacopla la session de las ciudades
     */
    private void handleUnsubscription(WebSocketSession session) {
        removeSessionFromAllSubscriptions(session);
//        sendMessageToSession(session, createSimpleMessage("unsubscription_confirmed",
//                "Desuscrito de todas las actualizaciones"));
        logger.info("Sesion {} desuscrita de todas las actualizaciones", session.getId());
    }

    /**
     * Permite desacoplar la session de la ciudad o ciudades vinculadas
     * MULTI-TENANT: También limpia el tenantId de la sesión
     */
    private void removeSessionFromAllSubscriptions(WebSocketSession session) {
        String sessionId = session.getId();
        Integer previousCityId = sessionCityMap.remove(sessionId);

        // MULTI-TENANT: Limpiar tenantId
        sessionTenantMap.remove(sessionId);

        if (previousCityId != null) {
            // Era una suscripción específica de ciudad
            CopyOnWriteArraySet<WebSocketSession> sessions = citySessions.get(previousCityId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    citySessions.remove(previousCityId);
                }
            }
        } else {
            // Podría estar en suscripción de todas las ciudades
            allCitiesSessions.remove(session);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        logger.error("Error en transporte WebSocket para sesion {}: {}",
                session.getId(), exception.getMessage());
        removeSessionFromAllSubscriptions(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        removeSessionFromAllSubscriptions(session);
        logger.info("Conexion WebSocket cerrada. ID: {}. Estado: {}. Conexiones activas: {}",
                session.getId(), closeStatus, getActiveSessionsCount());
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    /**
     * Envía ubicaciones de riders a sesiones suscritas a una ciudad específica
     * MULTI-TENANT: Solo envía a sesiones del mismo tenant
     *
     * @param tenantId Tenant ID propietario de los datos
     * @param cityId Ciudad ID
     * @param locations Ubicaciones de riders
     */
    public void broadcastRiderLocationsByCity(Long tenantId, Integer cityId, List<RiderLocationDto> locations) {
//        logger.info("=== BROADCAST DEBUG ===");
//        logger.info("CityId recibido: {}", cityId);
//        logger.info("Locations a enviar: {}", locations.size());
//        logger.info("Sesiones en citySessions para ciudad {}: {}", cityId,
//                citySessions.containsKey(cityId) ? citySessions.get(cityId).size() : 0);
//        logger.info("Sesiones totales en allCitiesSessions: {}", allCitiesSessions.size());

        if (locations.isEmpty()) {
            logger.debug("Tenant {}, Ciudad {}: No hay ubicaciones para enviar", tenantId, cityId);
            return;
        }

        // MULTI-TENANT: Filtrar sesiones específicas de esta ciudad que pertenecen al tenant
        CopyOnWriteArraySet<WebSocketSession> citySpecificSessions = citySessions.get(cityId);
        if (citySpecificSessions != null && !citySpecificSessions.isEmpty()) {
            Set<WebSocketSession> tenantSessions = filterSessionsByTenant(citySpecificSessions, tenantId);
            if (!tenantSessions.isEmpty()) {
                broadcastToSessions(tenantSessions, createLocationMessage(locations, cityId));
                logger.debug("Tenant {}, Ciudad {}: Enviadas {} ubicaciones a {} sesiones",
                        tenantId, cityId, locations.size(), tenantSessions.size());
            }
        }

        // MULTI-TENANT: Filtrar sesiones de todas las ciudades que pertenecen al tenant
        if (!allCitiesSessions.isEmpty()) {
            Set<WebSocketSession> tenantSessions = filterSessionsByTenant(allCitiesSessions, tenantId);
            if (!tenantSessions.isEmpty()) {
                broadcastToSessions(tenantSessions, createLocationMessage(locations, cityId));
                logger.debug("Tenant {}: Enviadas {} ubicaciones a {} sesiones (todas las ciudades)",
                        tenantId, locations.size(), tenantSessions.size());
            }
        }
    }

    /**
     * MULTI-TENANT: Filtra sesiones que pertenecen a un tenant específico
     */
    private Set<WebSocketSession> filterSessionsByTenant(Set<WebSocketSession> sessions, Long tenantId) {
        Set<WebSocketSession> filtered = new HashSet<>();
        for (WebSocketSession session : sessions) {
            Long sessionTenant = sessionTenantMap.get(session.getId());
            if (tenantId.equals(sessionTenant)) {
                filtered.add(session);
            }
        }
        return filtered;
    }

    /**
     * Broadcast a un conjunto específico de sesiones
     */
    private void broadcastToSessions(Set<WebSocketSession> sessions, RiderMessage message) {
        try {
            String jsonMessage = objectMapper.writeValueAsString(message);

            sessions.stream().forEach(session -> {
                synchronized (session) {
                    if (session.isOpen()) {
                        try {
                            session.sendMessage(new TextMessage(jsonMessage));
                        } catch (IOException e) {
                            logger.error("Error enviando mensaje a sesion {}: {}", session.getId(), e.getMessage());
                            removeSessionFromAllSubscriptions(session);
                        }
                    } else {
                        removeSessionFromAllSubscriptions(session);
                    }
                }
            });

        } catch (Exception e) {
            logger.error("Error al serializar y enviar ubicaciones de riders", e);
        }
    }

    /**
     * ################################################################################################################
     * METHODS AUXILIARES INTERNOS
     * ################################################################################################################
     */

    private void sendMessageToSession(WebSocketSession session, String message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(message));
            }
        } catch (IOException e) {
            logger.error("Error enviando mensaje a sesion {}: {}", session.getId(), e.getMessage());
            removeSessionFromAllSubscriptions(session);
        }
    }

    private RiderMessage createLocationMessage(List<RiderLocationDto> locations, Integer cityId) {
        Map<String, Object> data = new HashMap<>();
        data.put("locations", locations);
        data.put("city_id", cityId);
        data.put("count", locations.size());

        return new RiderMessage("rider_locations", data, OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    }

    private String createWelcomeMessage() {
        try {
            Map<String, Object> welcomeData = new HashMap<>();
            welcomeData.put("message", "Conexion establecida correctamente");
            welcomeData.put("instructions", Map.of(
                    "subscribe_city", "Envía {'action':'subscribe_city','city_id':123} para suscribirte a una ciudad específica",
                    "subscribe_all", "Envía {'action':'subscribe_all'} para recibir actualizaciones de todas las ciudades",
                    "unsubscribe", "Envía {'action':'unsubscribe'} para desuscribirte"
            ));

            RiderMessage welcomeMsg = new RiderMessage("connection_established", welcomeData, OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            return objectMapper.writeValueAsString(welcomeMsg);
        } catch (Exception e) {
            return "{\"type\":\"connection_established\",\"message\":\"Bienvenido\"}";
        }
    }

    private String createSimpleMessage(String type, String message) {
        try {
            RiderMessage msg = new RiderMessage(type, message, OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            return objectMapper.writeValueAsString(msg);
        } catch (Exception e) {
            return String.format("{\"type\":\"%s\",\"message\":\"%s\"}", type, message);
        }
    }

    private String createErrorMessage(String error) {
        try {
            RiderMessage errorMsg = new RiderMessage("error", error, OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            return objectMapper.writeValueAsString(errorMsg);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"message\":\"" + error + "\"}";
        }
    }

    public int getActiveSessionsCount() {
        return sessionCityMap.size() + allCitiesSessions.size();
    }

    public Map<String, Object> getConnectionStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_sessions", sessionCityMap.size());
        stats.put("all_cities_sessions", allCitiesSessions.size());
        stats.put("city_specific_sessions", citySessions.size());

        Map<Integer, Integer> citySessionCounts = new HashMap<>();
        citySessions.forEach((cityId, sessions) ->
                citySessionCounts.put(cityId, sessions.size()));
        stats.put("sessions_per_city", citySessionCounts);

        return stats;
    }

    public void destroy() {
        scheduler.shutdown();
        sessionTenantMap.clear();
        sessionCityMap.clear();
        citySessions.clear();
        allCitiesSessions.clear();
    }

    // ################################################################################################################
    // DTO del Rider Message en un futuro meterlo en un archivo aparte
    public static class RiderMessage {
        private String type;
        private Object data;
        private String timestamp;

        public RiderMessage(String type, Object data, String timestamp) {
            this.type = type;
            this.data = data;
            this.timestamp = timestamp;
        }

        // Getters y setters
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Object getData() { return data; }
        public void setData(Object data) { this.data = data; }
        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    }
}