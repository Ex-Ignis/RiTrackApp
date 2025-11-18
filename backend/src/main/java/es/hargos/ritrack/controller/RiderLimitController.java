package es.hargos.ritrack.controller;

import es.hargos.ritrack.client.HargosAuthClient;
import es.hargos.ritrack.context.TenantContext;
import es.hargos.ritrack.entity.RiderLimitWarningEntity;
import es.hargos.ritrack.service.RiderLimitService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller for rider limit warnings (authenticated endpoints).
 * Requires JWT authentication - TenantContext is set automatically.
 */
@RestController
@RequestMapping("/api/v1/rider-limit")
@RequiredArgsConstructor
public class RiderLimitController {

    private static final Logger logger = LoggerFactory.getLogger(RiderLimitController.class);

    private final RiderLimitService riderLimitService;
    private final HargosAuthClient hargosAuthClient;
    private final es.hargos.ritrack.repository.TenantRepository tenantRepository;

    /**
     * Get the active rider limit warning for the current tenant.
     * Uses TenantContext set by JwtAuthenticationFilter.
     *
     * @return Active warning with rider count, limit, excess, and grace period info
     */
    @GetMapping("/warning")
    public ResponseEntity<RiderLimitWarningResponse> getActiveWarning() {
        try {
            // Get tenant ID from context (set by JWT filter)
            TenantContext.TenantInfo tenantInfo = TenantContext.getCurrentContext();

            if (tenantInfo == null) {
                logger.warn("❌ [RiderLimitController] No tenant context found");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new RiderLimitWarningResponse(null, null, null, null, null, false));
            }

            logger.info("🔍 [RiderLimitController] TenantContext - selectedTenantId: {}, selectedSchema: {}, allTenantIds: {}, allSchemas: {}",
                    tenantInfo.getSelectedTenantId(),
                    tenantInfo.getSelectedSchemaName(),
                    tenantInfo.getTenantIds(),
                    tenantInfo.getSchemaNames());

            Long tenantId = tenantInfo.getSelectedTenantId();
            String schemaName = tenantInfo.getSelectedSchemaName();

            logger.info("📞 [RiderLimitController] Fetching active warning for tenantId: {}, schema: {}", tenantId, schemaName);

            // Get hargosTenantId for API calls
            var tenant = tenantRepository.findById(tenantId).orElse(null);
            Long hargosTenantId = tenant != null ? tenant.getHargosTenantId() : null;

            // Get current rider count and limit from services
            int currentCount = 0;
            Integer riderLimit = null;

            try {
                currentCount = riderLimitService.countRidersInGlovo(tenantId);

                if (hargosTenantId != null) {
                    HargosAuthClient.TenantInfoResponse tenantInfoResponse = hargosAuthClient.getTenantInfo(hargosTenantId);
                    riderLimit = tenantInfoResponse.getRiderLimit();
                }
            } catch (Exception e) {
                logger.error("Error getting rider count or limit: {}", e.getMessage());
            }

            // Get active warning from service (uses TenantContext automatically)
            RiderLimitWarningEntity warning = riderLimitService.getActiveWarning();

            if (warning == null) {
                logger.info("✅ [RiderLimitController] No active warning found for schema: {} - Returning current data: currentCount={}, limit={}",
                    schemaName, currentCount, riderLimit);
                return ResponseEntity.ok(new RiderLimitWarningResponse(currentCount, riderLimit, null, null, null, false));
            }

            logger.info("⚠️ [RiderLimitController] Active warning found - ID: {}, current={}, limit={}, expires={}, isResolved={}",
                warning.getId(), warning.getCurrentCount(), warning.getAllowedLimit(), warning.getExpiresAt(), warning.getIsResolved());

            // Build response
            RiderLimitWarningResponse response = new RiderLimitWarningResponse(
                warning.getCurrentCount(),
                warning.getAllowedLimit(),
                warning.getExcessCount(),
                warning.getExpiresAt(),
                warning.getCreatedAt(),
                true // isActive (if we found it, it's active)
            );

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error fetching rider limit warning: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new RiderLimitWarningResponse(null, null, null, null, null, false));
        }
    }

    // ==================== DTOs ====================

    public record RiderLimitWarningResponse(
            Integer currentCount,
            Integer allowedLimit,
            Integer excessCount,
            java.time.LocalDateTime expiresAt,
            java.time.LocalDateTime createdAt,
            boolean isActive
    ) {
    }
}
