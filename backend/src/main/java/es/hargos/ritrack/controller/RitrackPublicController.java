package es.hargos.ritrack.controller;

import es.hargos.ritrack.entity.TenantEntity;
import es.hargos.ritrack.repository.TenantRepository;
import es.hargos.ritrack.service.RiderLimitService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Public endpoints for external applications (HargosAuth) to query RiTrack data.
 * These endpoints do NOT require JWT authentication but are restricted to internal services.
 *
 * SECURITY: In production, these endpoints should be restricted by IP whitelist or API key.
 */
@RestController
@RequestMapping("/api/ritrack-public")
@RequiredArgsConstructor
public class RitrackPublicController {

    private static final Logger logger = LoggerFactory.getLogger(RitrackPublicController.class);

    private final TenantRepository tenantRepository;
    private final RiderLimitService riderLimitService;

    /**
     * Get the actual rider count for a tenant by HargosAuth tenant ID.
     * Used by HargosAuth to validate subscription downgrades.
     *
     * @param hargosTenantId HargosAuth tenant ID
     * @return Actual rider count from Glovo API
     */
    @GetMapping("/tenant/{hargosTenantId}/rider-count")
    public ResponseEntity<RiderCountResponse> getRiderCount(@PathVariable Long hargosTenantId) {
        logger.info("HargosAuth requesting rider count for hargosTenantId={}", hargosTenantId);

        try {
            // Find RiTrack tenant by HargosAuth tenant ID
            TenantEntity tenant = tenantRepository.findByHargosTenantId(hargosTenantId)
                    .orElse(null);

            // If tenant not found or not configured yet, return 0
            if (tenant == null) {
                logger.warn("Tenant with hargosTenantId={} not found in RiTrack", hargosTenantId);
                return ResponseEntity.ok(new RiderCountResponse(0, "Tenant no configurado en RiTrack"));
            }

            // Count riders in Glovo
            int riderCount = riderLimitService.countRidersInGlovo(tenant.getId());

            logger.info("HargosAuth tenant {} has {} riders in Glovo", hargosTenantId, riderCount);

            return ResponseEntity.ok(new RiderCountResponse(riderCount, "OK"));

        } catch (Exception e) {
            logger.error("Error counting riders for hargosTenantId={}: {}", hargosTenantId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new RiderCountResponse(null, "Error al obtener conteo de riders: " + e.getMessage()));
        }
    }

    /**
     * Re-check rider limits and resolve warning if tenant is now within limit.
     * Called by HargosAuth after subscription update.
     *
     * @param hargosTenantId HargosAuth tenant ID
     * @return Result of the recheck operation
     */
    @PostMapping("/tenant/{hargosTenantId}/recheck-limits")
    public ResponseEntity<RecheckLimitsResponse> recheckLimits(@PathVariable Long hargosTenantId) {
        logger.info("HargosAuth requesting limit recheck for hargosTenantId={}", hargosTenantId);

        try {
            // Find RiTrack tenant by HargosAuth tenant ID
            TenantEntity tenant = tenantRepository.findByHargosTenantId(hargosTenantId)
                    .orElse(null);

            if (tenant == null) {
                logger.warn("Tenant with hargosTenantId={} not found in RiTrack", hargosTenantId);
                return ResponseEntity.ok(new RecheckLimitsResponse(false, "Tenant no configurado en RiTrack"));
            }

            // Re-check limits and resolve if possible using explicit schema
            boolean resolved = riderLimitService.recheckAndResolveIfPossible(
                    tenant.getId(),
                    hargosTenantId,
                    tenant.getSchemaName()
            );

            if (resolved) {
                logger.info("HargosAuth tenant {}: Warning resolved after limit update", hargosTenantId);
                return ResponseEntity.ok(new RecheckLimitsResponse(true, "Warning resuelto - ahora dentro del límite"));
            } else {
                logger.info("HargosAuth tenant {}: No warning to resolve or still exceeds limit", hargosTenantId);
                return ResponseEntity.ok(new RecheckLimitsResponse(false, "Sin cambios - no hay warning activo o aún excede el límite"));
            }

        } catch (Exception e) {
            logger.error("Error rechecking limits for hargosTenantId={}: {}", hargosTenantId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new RecheckLimitsResponse(false, "Error al re-verificar límites: " + e.getMessage()));
        }
    }

    // ==================== DTOs ====================

    public record RiderCountResponse(Integer riderCount, String message) {
    }

    public record RecheckLimitsResponse(boolean resolved, String message) {
    }
}
