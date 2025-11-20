package es.hargos.ritrack.service;

import es.hargos.ritrack.client.HargosAuthClient;
import es.hargos.ritrack.entity.RiderLimitWarningEntity;
import es.hargos.ritrack.repository.RiderLimitWarningRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing rider limit validations and warnings.
 *
 * This service:
 * - Validates rider limits during onboarding
 * - Blocks rider creation if limit is exceeded
 * - Manages grace periods (7 days) for limit violations
 * - Communicates with HargosAuth for limit information
 *
 * Multi-tenant: Operates on current tenant's schema (via TenantContext)
 */
@Service
@RequiredArgsConstructor
public class RiderLimitService {

    private static final Logger logger = LoggerFactory.getLogger(RiderLimitService.class);
    private static final int GRACE_PERIOD_DAYS = 7;

    private final HargosAuthClient hargosAuthClient;
    private final RiderLimitWarningRepository warningRepository;
    private final RoosterCacheService roosterCache;
    private final jakarta.persistence.EntityManager entityManager;

    /**
     * Count the actual number of riders in Glovo API for the tenant.
     *
     * @param tenantId RiTrack tenant ID
     * @return Number of riders (employees) in Glovo
     * @throws Exception if Glovo API call fails
     */
    public int countRidersInGlovo(Long tenantId) throws Exception {
        logger.debug("Tenant {}: Counting riders in Glovo API", tenantId);

        List<?> employees = roosterCache.getAllEmployees(tenantId);
        int count = employees != null ? employees.size() : 0;

        logger.info("Tenant {}: Found {} riders in Glovo", tenantId, count);
        return count;
    }

    /**
     * Validate rider limits during onboarding.
     * If the tenant has more riders than allowed, creates a warning with 7-day grace period.
     *
     * @param ritrackTenantId RiTrack tenant ID
     * @param hargosTenantId HargosAuth tenant ID
     * @return Warning created if limit exceeded, null if within limit
     * @throws Exception if validation fails
     */
    @Transactional
    public RiderLimitWarningEntity validateDuringOnboarding(Long ritrackTenantId, Long hargosTenantId) throws Exception {
        logger.info("Tenant {}: Validating rider limits during onboarding", ritrackTenantId);

        // 1. Count current riders in Glovo
        int currentCount = countRidersInGlovo(ritrackTenantId);

        // 2. Get rider limit from HargosAuth
        HargosAuthClient.TenantInfoResponse tenantInfo = hargosAuthClient.getTenantInfo(hargosTenantId);
        Integer riderLimit = tenantInfo.getRiderLimit();

        // 3. If riderLimit is NULL = unlimited (no validation needed)
        if (riderLimit == null) {
            logger.info("Tenant {}: Unlimited riders (riderLimit=NULL), no validation required", ritrackTenantId);
            return null;
        }

        // 4. Check if within limit
        if (currentCount <= riderLimit) {
            logger.info("Tenant {}: Within limit - current={}, limit={}", ritrackTenantId, currentCount, riderLimit);
            return null;
        }

        // 5. EXCEEDS LIMIT - Create warning
        int excessCount = currentCount - riderLimit;
        logger.warn("Tenant {}: EXCEEDS LIMIT - current={}, limit={}, excess={}",
            ritrackTenantId, currentCount, riderLimit, excessCount);

        // Check if there's already an active warning
        Optional<RiderLimitWarningEntity> existingWarning = warningRepository.findActiveWarning();
        if (existingWarning.isPresent()) {
            logger.warn("Tenant {}: Active warning already exists, updating it", ritrackTenantId);
            RiderLimitWarningEntity warning = existingWarning.get();
            warning.setCurrentCount(currentCount);
            warning.setAllowedLimit(riderLimit);
            warning.setExcessCount(excessCount);
            warning.setUpdatedAt(LocalDateTime.now());
            warningRepository.save(warning);
            return warning;
        }

        // Create new warning
        RiderLimitWarningEntity warning = RiderLimitWarningEntity.builder()
            .currentCount(currentCount)
            .allowedLimit(riderLimit)
            .excessCount(excessCount)
            .expiresAt(LocalDateTime.now().plusDays(GRACE_PERIOD_DAYS))
            .isResolved(false)
            .build();

        warningRepository.save(warning);

        logger.info("Tenant {}: Warning created with ID {}, expires on {}",
            ritrackTenantId, warning.getId(), warning.getExpiresAt());

        // 6. Notify HargosAuth
        try {
            hargosAuthClient.reportLimitExceeded(hargosTenantId, currentCount, riderLimit);
        } catch (Exception e) {
            logger.error("Tenant {}: Failed to report limit exceeded to HargosAuth: {}",
                ritrackTenantId, e.getMessage());
            // Don't fail onboarding if notification fails
        }

        return warning;
    }

    /**
     * Validate if the tenant can create a new rider.
     *
     * Checks:
     * 1. If there's an active warning -> BLOCK (even during grace period)
     * 2. If creating would exceed limit -> BLOCK
     *
     * @param ritrackTenantId RiTrack tenant ID
     * @param hargosTenantId HargosAuth tenant ID
     * @throws IllegalStateException if creation would exceed limit or grace period expired
     * @throws Exception if validation fails
     */
    public void validateBeforeCreatingRider(Long ritrackTenantId, Long hargosTenantId) throws Exception {
        logger.debug("Tenant {}: Validating before creating rider", ritrackTenantId);

        // 1. Check if there's an active warning
        Optional<RiderLimitWarningEntity> activeWarning = warningRepository.findActiveWarning();

        if (activeWarning.isPresent()) {
            RiderLimitWarningEntity warning = activeWarning.get();

            // Check if grace period expired
            if (warning.isExpired()) {
                logger.error("Tenant {}: Grace period EXPIRED - blocking creation", ritrackTenantId);
                throw new IllegalStateException(
                    "No puedes crear riders. Tu periodo de gracia expiró el " +
                    warning.getExpiresAt().toLocalDate() + ". " +
                    "Actualiza tu suscripción o contacta con soporte."
                );
            }

            // Grace period still active but cannot create new riders
            logger.warn("Tenant {}: Active warning - blocking creation during grace period", ritrackTenantId);
            throw new IllegalStateException(
                String.format(
                    "No puedes crear más riders. Tienes %d riders pero tu límite es %d. " +
                    "Actualiza tu suscripción antes del %s.",
                    warning.getCurrentCount(),
                    warning.getAllowedLimit(),
                    warning.getExpiresAt().toLocalDate()
                )
            );
        }

        // 2. No active warning - validate with current count + 1
        int currentCount = countRidersInGlovo(ritrackTenantId);
        int afterCreation = currentCount + 1;

        boolean canCreate = hargosAuthClient.validateRiderLimit(hargosTenantId, afterCreation);

        if (!canCreate) {
            HargosAuthClient.TenantInfoResponse tenantInfo = hargosAuthClient.getTenantInfo(hargosTenantId);
            logger.error("Tenant {}: Creating rider would exceed limit - current={}, limit={}",
                ritrackTenantId, currentCount, tenantInfo.getRiderLimit());

            // Report to HargosAuth
            hargosAuthClient.reportLimitExceeded(hargosTenantId, afterCreation, tenantInfo.getRiderLimit());

            throw new IllegalStateException(
                String.format(
                    "No puedes crear más riders. Actualmente tienes %d riders y tu límite es %d. " +
                    "Actualiza tu suscripción para continuar.",
                    currentCount, tenantInfo.getRiderLimit()
                )
            );
        }

        logger.debug("Tenant {}: Validation OK - can create rider ({}/{})",
            ritrackTenantId, afterCreation, "limit");
    }

    /**
     * Check if the tenant is blocked due to expired grace period.
     *
     * @param ritrackTenantId RiTrack tenant ID
     * @return true if tenant is blocked (cannot use RiTrack)
     */
    public boolean isTenantBlocked(Long ritrackTenantId) {
        Optional<RiderLimitWarningEntity> activeWarning = warningRepository.findActiveWarning();

        if (activeWarning.isEmpty()) {
            return false;
        }

        RiderLimitWarningEntity warning = activeWarning.get();
        boolean expired = warning.isExpired();

        if (expired) {
            logger.warn("Tenant {}: BLOCKED - grace period expired on {}",
                ritrackTenantId, warning.getExpiresAt());
        }

        return expired;
    }

    /**
     * Get the active warning for the tenant (if exists).
     *
     * @return Active warning or null
     */
    public RiderLimitWarningEntity getActiveWarning() {
        return warningRepository.findActiveWarning().orElse(null);
    }

    /**
     * Get the active warning for the current tenant (uses tenant context).
     *
     * @param schemaName Tenant schema name (not used, kept for compatibility)
     * @return Active warning or null
     */
    public RiderLimitWarningEntity getActiveWarningBySchema(String schemaName) {
        return warningRepository.findActiveWarning().orElse(null);
    }

    /**
     * Resolve a warning (mark as resolved).
     * This happens when the tenant upgrades subscription or reduces rider count.
     *
     * @param warningId Warning ID
     * @param resolvedBy Who resolved it (user email or "system")
     * @param note Resolution note
     */
    @Transactional
    public void resolveWarning(Long warningId, String resolvedBy, String note) {
        RiderLimitWarningEntity warning = warningRepository.findById(warningId)
            .orElseThrow(() -> new IllegalArgumentException("Warning not found: " + warningId));

        warning.setIsResolved(true);
        warning.setResolvedAt(LocalDateTime.now());
        warning.setResolvedBy(resolvedBy);
        warning.setResolutionNote(note);

        warningRepository.save(warning);

        logger.info("Warning {} resolved by {} - Note: {}", warningId, resolvedBy, note);
    }

    /**
     * Re-check limits (called after subscription update or manual trigger).
     * If tenant is now within limit, resolve the warning automatically.
     *
     * This method works for public endpoints by using native SQL with explicit schema.
     *
     * @param ritrackTenantId RiTrack tenant ID
     * @param hargosTenantId HargosAuth tenant ID
     * @param schemaName Tenant schema name
     * @return true if warning was resolved, false otherwise
     * @throws Exception if validation fails
     */
    @Transactional
    public boolean recheckAndResolveIfPossible(Long ritrackTenantId, Long hargosTenantId, String schemaName) throws Exception {
        logger.info("Tenant {}: Re-checking rider limits in schema: {}", ritrackTenantId, schemaName);

        // Validate schema name to prevent SQL injection
        if (!isValidSchemaName(schemaName)) {
            logger.error("Invalid schema name: {}", schemaName);
            throw new IllegalArgumentException("Invalid schema name");
        }

        // Find active warning using NATIVE QUERY with EXPLICIT SCHEMA
        String selectSql = String.format(
            "SELECT * FROM %s.rider_limit_warnings " +
            "WHERE is_resolved = false " +
            "ORDER BY created_at DESC LIMIT 1",
            schemaName
        );

        logger.debug("Executing SQL: {}", selectSql);

        List<RiderLimitWarningEntity> results = entityManager
            .createNativeQuery(selectSql, RiderLimitWarningEntity.class)
            .getResultList();

        if (results.isEmpty()) {
            logger.debug("Tenant {}: No active warning to resolve", ritrackTenantId);
            return false;
        }

        RiderLimitWarningEntity activeWarning = results.get(0);
        logger.info("Tenant {}: Found active warning ID={}", ritrackTenantId, activeWarning.getId());

        // Count current riders
        int currentCount = countRidersInGlovo(ritrackTenantId);

        // Get current limit from AuthSystem
        HargosAuthClient.TenantInfoResponse tenantInfo = hargosAuthClient.getTenantInfo(hargosTenantId);
        Integer riderLimit = tenantInfo.getRiderLimit();

        logger.info("Tenant {}: Current riders={}, riderLimit={}", ritrackTenantId, currentCount, riderLimit);

        // If riderLimit is NULL or within limit -> resolve
        if (riderLimit == null || currentCount <= riderLimit) {
            String note = String.format(
                "Auto-resolved: Current riders (%d) now within limit (%s)",
                currentCount,
                riderLimit == null ? "unlimited" : riderLimit.toString()
            );

            // UPDATE using NATIVE QUERY with EXPLICIT SCHEMA
            String updateSql = String.format(
                "UPDATE %s.rider_limit_warnings " +
                "SET is_resolved = true, " +
                "    resolved_at = CURRENT_TIMESTAMP, " +
                "    resolved_by = 'system', " +
                "    resolution_note = :note, " +
                "    updated_at = CURRENT_TIMESTAMP " +
                "WHERE id = :warningId",
                schemaName
            );

            logger.debug("Executing UPDATE SQL for warning ID={}", activeWarning.getId());

            int updatedRows = entityManager.createNativeQuery(updateSql)
                .setParameter("note", note)
                .setParameter("warningId", activeWarning.getId())
                .executeUpdate();

            entityManager.flush();

            logger.info("Tenant {}: Warning {} auto-resolved ({} rows updated) - current={}, limit={}",
                ritrackTenantId, activeWarning.getId(), updatedRows, currentCount, riderLimit);

            return true;
        }

        logger.info("Tenant {}: Still exceeds limit - current={}, limit={}",
            ritrackTenantId, currentCount, riderLimit);

        return false;
    }

    /**
     * Validate schema name to prevent SQL injection.
     * Schema names should only contain alphanumeric characters, underscores, and hyphens.
     */
    private boolean isValidSchemaName(String schemaName) {
        if (schemaName == null || schemaName.trim().isEmpty()) {
            return false;
        }
        // Allow alphanumeric, underscores, and hyphens only
        return schemaName.matches("^[a-zA-Z0-9_-]+$");
    }
}
