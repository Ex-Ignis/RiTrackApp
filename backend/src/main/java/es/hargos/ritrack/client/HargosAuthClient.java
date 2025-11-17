package es.hargos.ritrack.client;

import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP Client for communicating with HargosAuth backend.
 *
 * Used for:
 * - Validating rider limits before creating riders
 * - Reporting limit violations to SUPER_ADMIN
 * - Getting tenant configuration from HargosAuth
 */
@Component
public class HargosAuthClient {

    private static final Logger logger = LoggerFactory.getLogger(HargosAuthClient.class);

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${hargos.auth.base-url:http://localhost:8081}")
    private String hargosAuthBaseUrl;

    /**
     * Get tenant information including rider_limit from HargosAuth.
     *
     * @param hargosTenantId Tenant ID in HargosAuth (auth.tenants.id)
     * @return Tenant information including riderLimit
     * @throws RuntimeException if HargosAuth is unreachable or tenant not found
     */
    public TenantInfoResponse getTenantInfo(Long hargosTenantId) {
        String url = hargosAuthBaseUrl + "/api/ritrack/tenant-info/" + hargosTenantId;

        try {
            logger.debug("Fetching tenant info from HargosAuth: tenantId={}", hargosTenantId);

            ResponseEntity<TenantInfoResponse> response = restTemplate.getForEntity(
                url,
                TenantInfoResponse.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                logger.debug("Tenant info retrieved: tenantId={}, riderLimit={}",
                    hargosTenantId, response.getBody().getRiderLimit());
                return response.getBody();
            }

            throw new RuntimeException("Failed to get tenant info: unexpected response");

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                logger.error("Tenant not found in HargosAuth: tenantId={}", hargosTenantId);
                throw new RuntimeException("Tenant not found in HargosAuth: " + hargosTenantId);
            }
            logger.error("Error calling HargosAuth /tenant-info: status={}, message={}",
                e.getStatusCode(), e.getMessage());
            throw new RuntimeException("Failed to get tenant info from HargosAuth", e);
        } catch (Exception e) {
            logger.error("Error communicating with HargosAuth: {}", e.getMessage(), e);
            throw new RuntimeException("HargosAuth is unreachable", e);
        }
    }

    /**
     * Report to HargosAuth that a tenant has exceeded their rider limit.
     * This creates a notification for SUPER_ADMIN to review.
     *
     * @param hargosTenantId Tenant ID in HargosAuth
     * @param currentCount Current number of riders detected
     * @param allowedLimit Maximum riders allowed by subscription
     */
    public void reportLimitExceeded(Long hargosTenantId, Integer currentCount, Integer allowedLimit) {
        String url = hargosAuthBaseUrl + "/api/ritrack/report-limit-exceeded";

        Map<String, Object> request = new HashMap<>();
        request.put("tenantId", hargosTenantId);
        request.put("currentCount", currentCount);
        request.put("allowedLimit", allowedLimit);
        request.put("excessCount", currentCount - allowedLimit);

        try {
            logger.info("Reporting limit exceeded to HargosAuth: tenantId={}, current={}, limit={}",
                hargosTenantId, currentCount, allowedLimit);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<Void> response = restTemplate.postForEntity(url, entity, Void.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                logger.info("Limit exceeded notification sent successfully to HargosAuth");
            } else {
                logger.warn("Unexpected response when reporting limit exceeded: status={}",
                    response.getStatusCode());
            }

        } catch (Exception e) {
            // Don't throw exception - this is a notification, not critical for operation
            logger.error("Failed to report limit exceeded to HargosAuth: {}", e.getMessage(), e);
        }
    }

    /**
     * Validate if a tenant can have the specified number of riders.
     *
     * @param hargosTenantId Tenant ID in HargosAuth
     * @param currentCount Number of riders to validate
     * @return true if within limit, false otherwise
     * @throws RuntimeException if HargosAuth is unreachable
     */
    public boolean validateRiderLimit(Long hargosTenantId, Integer currentCount) {
        try {
            TenantInfoResponse tenantInfo = getTenantInfo(hargosTenantId);

            // If riderLimit is NULL, it means unlimited
            if (tenantInfo.getRiderLimit() == null) {
                logger.debug("Tenant {} has unlimited riders", hargosTenantId);
                return true;
            }

            boolean withinLimit = currentCount <= tenantInfo.getRiderLimit();

            logger.debug("Rider limit validation: tenantId={}, current={}, limit={}, withinLimit={}",
                hargosTenantId, currentCount, tenantInfo.getRiderLimit(), withinLimit);

            return withinLimit;

        } catch (Exception e) {
            logger.error("Failed to validate rider limit: {}", e.getMessage(), e);
            // FAIL-SAFE: If HargosAuth is down, deny the operation for security
            throw new RuntimeException("Cannot verify rider limit. Please try again later.", e);
        }
    }

    // ==================== DTOs ====================

    /**
     * Response from GET /api/ritrack/tenant-info/{tenantId}
     */
    @Data
    public static class TenantInfoResponse {
        private Long id;
        private String name;
        private String organizationName;
        private String appName;
        private Integer accountLimit;
        private Integer riderLimit;  // NULL = unlimited
        private Boolean isActive;
    }
}
