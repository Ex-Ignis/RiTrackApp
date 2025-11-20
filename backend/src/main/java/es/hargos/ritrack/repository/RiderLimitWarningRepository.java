package es.hargos.ritrack.repository;

import es.hargos.ritrack.entity.RiderLimitWarningEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for RiderLimitWarningEntity.
 *
 * Multi-tenant: Operates on the current tenant's schema set by TenantContext.
 */
@Repository
public interface RiderLimitWarningRepository extends JpaRepository<RiderLimitWarningEntity, Long> {

    /**
     * Find the active (unresolved) warning for the tenant.
     * There should only be one active warning at a time per tenant.
     *
     * @return Optional containing the active warning, or empty if none exists
     */
    @Query("SELECT w FROM RiderLimitWarningEntity w " +
           "WHERE w.isResolved = false " +
           "ORDER BY w.createdAt DESC")
    Optional<RiderLimitWarningEntity> findActiveWarning();

    /**
     * Find all warnings for the tenant, ordered by most recent first.
     *
     * @return List of all warnings (resolved and unresolved)
     */
    @Query("SELECT w FROM RiderLimitWarningEntity w " +
           "ORDER BY w.createdAt DESC")
    List<RiderLimitWarningEntity> findAllOrderByCreatedAtDesc();

    /**
     * Find all resolved warnings.
     *
     * @return List of resolved warnings
     */
    @Query("SELECT w FROM RiderLimitWarningEntity w " +
           "WHERE w.isResolved = true " +
           "ORDER BY w.resolvedAt DESC")
    List<RiderLimitWarningEntity> findResolved();

    /**
     * Find warnings that have expired but not yet resolved.
     * These tenants should be blocked from creating new riders.
     *
     * @return List of expired unresolved warnings
     */
    @Query("SELECT w FROM RiderLimitWarningEntity w " +
           "WHERE w.isResolved = false AND w.expiresAt < CURRENT_TIMESTAMP " +
           "ORDER BY w.expiresAt ASC")
    List<RiderLimitWarningEntity> findExpiredUnresolved();
}
