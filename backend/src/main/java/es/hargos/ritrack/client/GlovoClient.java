package es.hargos.ritrack.client;

import es.hargos.ritrack.entity.GlovoCredentialsEntity;
import es.hargos.ritrack.repository.GlovoCredentialsRepository;
import es.hargos.ritrack.service.ApiMonitoringService;
import es.hargos.ritrack.service.RateLimitService;
import es.hargos.ritrack.service.TenantTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Glovo API Client for Multi-Tenant Architecture.
 *
 * MULTI-TENANT SUPPORT:
 * - All methods require tenantId parameter
 * - Each tenant uses their own credentials from database
 * - Each tenant has independent rate limiting
 * - Each tenant gets their own OAuth2 token
 *
 * APIs:
 * - Rooster API: Employee management (CRUD operations)
 * - Live API: Real-time operations and locations
 */
@Component
public class GlovoClient {

    private static final Logger logger = LoggerFactory.getLogger(GlovoClient.class);

    @Autowired
    private TenantTokenService tokenService;

    @Autowired
    private GlovoCredentialsRepository credentialsRepository;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private ApiMonitoringService monitoringService;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Get Glovo credentials for a tenant
     */
    private GlovoCredentialsEntity getCredentials(Long tenantId) {
        return credentialsRepository
            .findByTenantIdAndIsActive(tenantId, true)
            .orElseThrow(() -> new RuntimeException(
                "No active Glovo credentials found for tenant " + tenantId
            ));
    }

    /**
     * Execute HTTP request with rate limiting and 429 monitoring
     */
    private <T> T executeWithRateLimit(Long tenantId,
                                        RateLimitService.RequestPriority priority,
                                        HttpRequestExecutor<T> executor) throws Exception {
        return executeWithRateLimit(tenantId, priority, executor, "Unknown", "GlovoClient");
    }

    /**
     * Execute HTTP request with rate limiting, 429 monitoring, and metadata
     */
    private <T> T executeWithRateLimit(Long tenantId,
                                        RateLimitService.RequestPriority priority,
                                        HttpRequestExecutor<T> executor,
                                        String endpoint,
                                        String callingService) throws Exception {
        return rateLimitService.executeWithRateLimit(tenantId, priority, () -> {
            try {
                return executor.execute();
            } catch (RateLimitService.RateLimitExceededException e) {
                // Registrar 429 en monitoreo
                monitoringService.recordRateLimitError(tenantId, endpoint, callingService);
                throw new RuntimeException(e);
            } catch (HttpClientErrorException e) {
                // Capturar 429s que vienen directamente de la API
                if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                    monitoringService.recordRateLimitError(tenantId, endpoint, callingService);
                }
                throw new RuntimeException(e);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, 3);
    }

    @FunctionalInterface
    private interface HttpRequestExecutor<T> {
        T execute() throws Exception;
    }

    // ========================================
    // ROOSTER API - EMPLOYEE MANAGEMENT
    // ========================================

    /**
     * Get all employees for a tenant
     * GET /v3/external/employees
     */
    public List<?> obtenerEmpleados(Long tenantId) throws Exception {
        return executeWithRateLimit(tenantId, RateLimitService.RequestPriority.HIGH, () -> {
            GlovoCredentialsEntity credentials = getCredentials(tenantId);
            String token = tokenService.getAccessToken(tenantId);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            String url = credentials.getRoosterBaseUrl() + "/v3/external/employees?size=10000";

            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<>() {}
            );

            return response.getBody();
        });
    }

    /**
     * Get employee by ID
     * GET /v3/external/employees/{employee_id}
     */
    public Object obtenerEmpleadoPorId(Long tenantId, int id) throws Exception {
        return executeWithRateLimit(tenantId, RateLimitService.RequestPriority.HIGH, () -> {
            GlovoCredentialsEntity credentials = getCredentials(tenantId);
            String token = tokenService.getAccessToken(tenantId);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            String url = credentials.getRoosterBaseUrl() + "/v3/external/employees/" + id;

            try {
                ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    new ParameterizedTypeReference<>() {}
                );
                return response.getBody();
            } catch (HttpClientErrorException.NotFound e) {
                return null;
            }
        });
    }

    /**
     * Update employee
     * PUT /v3/external/employees/{employee_id}
     */
    public Object actualizarEmpleado(Long tenantId, int employeeId, Map<String, Object> updateData) throws Exception {
        return executeWithRateLimit(tenantId, RateLimitService.RequestPriority.HIGH, () -> {
            GlovoCredentialsEntity credentials = getCredentials(tenantId);
            String token = tokenService.getAccessToken(tenantId);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(updateData, headers);

            String url = credentials.getRoosterBaseUrl() + "/v3/external/employees/" + employeeId;

            try {
                ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url,
                    HttpMethod.PUT,
                    entity,
                    new ParameterizedTypeReference<>() {}
                );
                return response.getBody();
            } catch (HttpClientErrorException.BadRequest e) {
                String responseBody = e.getResponseBodyAsString();
                logger.error("Error 400 updating employee {} for tenant {}: {}",
                    employeeId, tenantId, responseBody);

                if (responseBody.contains("email") && responseBody.contains("already be in use")) {
                    throw new RuntimeException("DUPLICATE_EMAIL:El email ya está en uso por otro rider");
                } else if (responseBody.contains("phone") && responseBody.contains("already be in use")) {
                    throw new RuntimeException("DUPLICATE_PHONE:El teléfono ya está en uso por otro rider");
                } else if (responseBody.contains("email or phone number may already be in use")) {
                    throw new RuntimeException("DUPLICATE_EMAIL_OR_PHONE:El email o teléfono ya está en uso");
                } else {
                    throw new RuntimeException("UPDATE_ERROR:" + responseBody);
                }
            }
        });
    }

    /**
     * Create employee
     * POST /v3/external/employees
     */
    public Object crearEmpleado(Long tenantId, Map<String, Object> employeeData) throws Exception {
        return executeWithRateLimit(tenantId, RateLimitService.RequestPriority.HIGH, () -> {
            GlovoCredentialsEntity credentials = getCredentials(tenantId);
            String token = tokenService.getAccessToken(tenantId);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(employeeData, headers);

            String url = credentials.getRoosterBaseUrl() + "/v3/external/employees";

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<>() {}
            );

            return response.getBody();
        });
    }

    /**
     * Assign starting points to employee
     * PUT /v3/external/employees/{employee_id}/starting-points
     */
    public Object asignarPuntosInicioAEmpleado(Long tenantId, int employeeId,
                                                Map<String, Object> startingPointsData) throws Exception {
        return executeWithRateLimit(tenantId, RateLimitService.RequestPriority.HIGH, () -> {
            GlovoCredentialsEntity credentials = getCredentials(tenantId);
            String token = tokenService.getAccessToken(tenantId);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(startingPointsData, headers);

            String url = credentials.getRoosterBaseUrl() + "/v3/external/employees/" +
                         employeeId + "/starting-points";

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url,
                HttpMethod.PUT,
                entity,
                new ParameterizedTypeReference<>() {}
            );

            return response.getBody();
        });
    }

    /**
     * Assign vehicles to employee
     * PUT /v3/external/employees/{employee_id}/vehicle-types
     */
    public Object asignarVehiculosAEmpleado(Long tenantId, int employeeId,
                                             Map<String, Object> vehicleData) throws Exception {
        return executeWithRateLimit(tenantId, RateLimitService.RequestPriority.HIGH, () -> {
            GlovoCredentialsEntity credentials = getCredentials(tenantId);
            String token = tokenService.getAccessToken(tenantId);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(vehicleData, headers);

            String url = credentials.getRoosterBaseUrl() + "/v3/external/employees/" +
                         employeeId + "/vehicle-types";

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url,
                HttpMethod.PUT,
                entity,
                new ParameterizedTypeReference<>() {}
            );

            return response.getBody();
        });
    }

    // ========================================
    // LIVE API - REAL-TIME OPERATIONS
    // ========================================

    /**
     * Get rider live data
     * GET /v2/external/rider/{rider_id}
     */
    public Object obtenerRiderLiveData(Long tenantId, int riderId) throws Exception {
        return executeWithRateLimit(tenantId, RateLimitService.RequestPriority.MEDIUM, () -> {
            GlovoCredentialsEntity credentials = getCredentials(tenantId);
            String token = tokenService.getAccessToken(tenantId);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            String url = credentials.getLiveBaseUrl() + "/v2/external/rider/" + riderId;

            try {
                ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    new ParameterizedTypeReference<>() {}
                );
                return response.getBody();
            } catch (HttpClientErrorException.NotFound e) {
                logger.warn("Rider {} not found in Live API for tenant {}", riderId, tenantId);
                return null;
            }
        });
    }

    /**
     * Get riders by city
     * GET /v1/external/city/{city_id}/riders
     */
    public Object obtenerRidersPorCiudad(Long tenantId, Integer cityId) throws Exception {
        return obtenerRidersPorCiudad(tenantId, cityId, null, 100, "id");
    }

    /**
     * Get riders by city with pagination
     * GET /v1/external/city/{city_id}/riders
     */
    public Object obtenerRidersPorCiudad(Long tenantId, Integer cityId,
                                          Integer page, Integer size, String sortBy) throws Exception {
        return executeWithRateLimit(tenantId, RateLimitService.RequestPriority.MEDIUM, () -> {
            GlovoCredentialsEntity credentials = getCredentials(tenantId);
            String token = tokenService.getAccessToken(tenantId);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            StringBuilder urlBuilder = new StringBuilder(credentials.getLiveBaseUrl() +
                "/v1/external/city/" + cityId + "/riders?");

            if (page != null) {
                urlBuilder.append("page=").append(page).append("&");
            }
            if (size != null) {
                urlBuilder.append("size=").append(size).append("&");
            }
            if (sortBy != null) {
                urlBuilder.append("sort_by=").append(sortBy);
            }

            String finalUrl = urlBuilder.toString();

            try {
                ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    finalUrl,
                    HttpMethod.GET,
                    entity,
                    new ParameterizedTypeReference<>() {}
                );

                return response.getBody();
            } catch (HttpClientErrorException.NotFound e) {
                logger.warn("City {} not found in Live API for tenant {}", cityId, tenantId);
                return null;
            }
        });
    }

    // ========================================
    // MASTER DATA - CITIES, CONTRACTS, VEHICLES
    // ========================================

    /**
     * Get contracts
     * GET /v3/external/contracts
     */
    public List<Object> obtenerContratos(Long tenantId) throws Exception {
        return executeWithRateLimit(tenantId, RateLimitService.RequestPriority.LOW, () -> {
            GlovoCredentialsEntity credentials = getCredentials(tenantId);
            String token = tokenService.getAccessToken(tenantId);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            String url = credentials.getRoosterBaseUrl() + "/v3/external/contracts";

            ResponseEntity<List<Object>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<>() {}
            );

            return response.getBody();
        });
    }

    /**
     * Get vehicle types
     * GET /v3/external/vehicle-types
     */
    public List<Object> obtenerTiposVehiculos(Long tenantId) throws Exception {
        return executeWithRateLimit(tenantId, RateLimitService.RequestPriority.LOW, () -> {
            GlovoCredentialsEntity credentials = getCredentials(tenantId);
            String token = tokenService.getAccessToken(tenantId);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            String url = credentials.getRoosterBaseUrl() + "/v3/external/vehicle-types";

            ResponseEntity<List<Object>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<>() {}
            );

            return response.getBody();
        });
    }

    /**
     * Get starting points by city
     * GET /v3/external/cities/{city_id}/starting-points
     */
    public List<Object> obtenerPuntosInicioPorCiudad(Long tenantId, Integer cityId) throws Exception {
        return executeWithRateLimit(tenantId, RateLimitService.RequestPriority.LOW, () -> {
            GlovoCredentialsEntity credentials = getCredentials(tenantId);
            String token = tokenService.getAccessToken(tenantId);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            String url = credentials.getRoosterBaseUrl() + "/v3/external/cities/" +
                         cityId + "/starting-points";

            ResponseEntity<List<Object>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<>() {}
            );

            return response.getBody();
        });
    }

    // ========================================
    // BATCH OPERATIONS
    // ========================================

    /**
     * Get all riders from a city (handles pagination automatically)
     */
    public List<Map<String, Object>> obtenerTodosLosRidersDeCiudad(Long tenantId, Integer cityId)
            throws Exception {
        List<Map<String, Object>> allRiders = new ArrayList<>();
        Integer page = 0;
        Integer size = 100;

        while (true) {
            Object response = obtenerRidersPorCiudad(tenantId, cityId, page, size, "id");

            if (response instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> responseMap = (Map<String, Object>) response;

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> riders =
                    (List<Map<String, Object>>) responseMap.get("data");

                if (riders == null || riders.isEmpty()) {
                    break;
                }

                allRiders.addAll(riders);

                @SuppressWarnings("unchecked")
                Map<String, Object> meta = (Map<String, Object>) responseMap.get("meta");
                if (meta != null) {
                    Integer currentPage = (Integer) meta.get("current_page");
                    Integer totalPages = (Integer) meta.get("total_pages");

                    if (currentPage == null || totalPages == null || currentPage >= totalPages - 1) {
                        break;
                    }
                }

                page++;
            } else {
                break;
            }
        }

        return allRiders;
    }

    /**
     * Get all riders from all configured cities for a tenant
     */
    public Map<Integer, List<Object>> obtenerTodosLosRiders(Long tenantId, List<Integer> activeCityIds) throws Exception {
        Map<Integer, List<Object>> ridersPorCiudad = new HashMap<>();

        for (Integer cityId : activeCityIds) {
            try {
                List<Map<String, Object>> riders = obtenerTodosLosRidersDeCiudad(tenantId, cityId);
                ridersPorCiudad.put(cityId, new ArrayList<>(riders));
                logger.info("Tenant {}: Obtenidos {} riders de ciudad {}",
                    tenantId, riders.size(), cityId);
            } catch (Exception e) {
                logger.error("Tenant {}: Error obteniendo riders de ciudad {}: {}",
                    tenantId, cityId, e.getMessage());
            }
        }

        return ridersPorCiudad;
    }

    // ========================================
    // UTILITY METHODS
    // ========================================

    /**
     * Check if tenant credentials are valid
     */
    public boolean verificarEstadoAPI(Long tenantId) {
        try {
            obtenerEmpleados(tenantId);
            return true;
        } catch (Exception e) {
            logger.error("API verification failed for tenant {}: {}", tenantId, e.getMessage());
            return false;
        }
    }

    /**
     * Check if Live API is working for a city
     */
    public boolean verificarEstadoAPILive(Long tenantId, Integer cityId) {
        try {
            obtenerRidersPorCiudad(tenantId, cityId);
            return true;
        } catch (Exception e) {
            logger.error("Live API verification failed for tenant {} city {}: {}",
                tenantId, cityId, e.getMessage());
            return false;
        }
    }

    /**
     * Check if city has active riders
     */
    public boolean ciudadTieneRidersActivos(Long tenantId, Integer cityId) {
        try {
            Object response = obtenerRidersPorCiudad(tenantId, cityId, 0, 1, "id");
            if (response instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) response;
                @SuppressWarnings("unchecked")
                List<?> data = (List<?>) map.get("data");
                return data != null && !data.isEmpty();
            }
            return false;
        } catch (Exception e) {
            logger.error("Error checking active riders for tenant {} city {}: {}",
                tenantId, cityId, e.getMessage());
            return false;
        }
    }

    // ========================================
    // CREDENTIAL VALIDATION (NO DB LOOKUP)
    // ========================================

    /**
     * Validates Glovo credentials by making a test API call.
     * This method is used during tenant onboarding when credentials are not yet saved to DB.
     *
     * @param accessToken OAuth2 access token obtained from Glovo
     * @param roosterBaseUrl Base URL for Glovo Rooster API
     * @return List of available cities from Glovo API
     * @throws Exception if API call fails
     */
    public List<Map<String, Object>> validateCredentialsWithToken(String accessToken, String roosterBaseUrl) throws Exception {
        String citiesUrl = roosterBaseUrl + "/v3/external/cities";

        logger.info("=== VALIDATING GLOVO CREDENTIALS ===");
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
            logger.info("Retrieved {} cities from Glovo API", cities != null ? cities.size() : 0);

            return cities;
        } catch (HttpClientErrorException | org.springframework.web.client.HttpServerErrorException e) {
            logger.error("=== GLOVO API VALIDATION ERROR ===");
            logger.error("Status: {}", e.getStatusCode());
            logger.error("Response Body: {}", e.getResponseBodyAsString());
            throw new Exception("Glovo API error " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        }
    }
}
