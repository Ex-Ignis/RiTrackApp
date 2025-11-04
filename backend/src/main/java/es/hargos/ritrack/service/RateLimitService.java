package es.hargos.ritrack.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Rate Limiter Service using Bucket4j to control requests to Glovo API.
 *
 * MULTI-TENANT ARCHITECTURE:
 * - Each tenant has its own Glovo API credentials
 * - Each tenant has its own rate limit: 1000 req/min (we use 900 for safety)
 * - Tenants do NOT share rate limits
 *
 * Example with 20 tenants:
 * - Tenant 1 (Arendel): 900 req/min
 * - Tenant 2 (Entregalia): 900 req/min
 * - ...
 * - Total capacity: 20 × 900 = 18,000 req/min
 *
 * Priority levels per tenant:
 * 1. HIGH: User-facing requests (searches, filters, detail views) - 400 req/min
 * 2. MEDIUM: WebSocket location updates - 300 req/min
 * 3. LOW: Background sync operations - 200 req/min
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    // Rate limit per tenant: 900 requests per minute
    private static final int TENANT_LIMIT = 900;
    private static final Duration REFILL_DURATION = Duration.ofMinutes(1);

    // Buckets per tenant
    private final ConcurrentMap<Long, TenantBuckets> tenantBucketsMap;

    public RateLimitService() {
        this.tenantBucketsMap = new ConcurrentHashMap<>();
    }

    /**
     * Buckets for a single tenant
     */
    @Data
    static class TenantBuckets {
        private final Bucket globalBucket;
        private final Map<RequestPriority, Bucket> priorityBuckets;

        public TenantBuckets(Bucket globalBucket, Map<RequestPriority, Bucket> priorityBuckets) {
            this.globalBucket = globalBucket;
            this.priorityBuckets = priorityBuckets;
        }
    }

    /**
     * Get or create buckets for a tenant
     */
    private TenantBuckets getOrCreateBuckets(Long tenantId) {
        return tenantBucketsMap.computeIfAbsent(tenantId, id -> {
            log.info("Creating rate limit buckets for tenant {}", id);

            // Global bucket: 900 req/min for this tenant
            Bandwidth globalLimit = Bandwidth.classic(
                TENANT_LIMIT,
                Refill.intervally(TENANT_LIMIT, REFILL_DURATION)
            );
            Bucket globalBucket = Bucket.builder()
                .addLimit(globalLimit)
                .build();

            // Priority-specific buckets
            Map<RequestPriority, Bucket> priorityBuckets = new HashMap<>();

            // HIGH priority: 400 req/min (user-facing requests)
            Bandwidth highPriorityLimit = Bandwidth.classic(
                400,
                Refill.intervally(400, REFILL_DURATION)
            );
            priorityBuckets.put(RequestPriority.HIGH,
                Bucket.builder().addLimit(highPriorityLimit).build());

            // MEDIUM priority: 300 req/min (WebSocket updates)
            Bandwidth mediumPriorityLimit = Bandwidth.classic(
                300,
                Refill.intervally(300, REFILL_DURATION)
            );
            priorityBuckets.put(RequestPriority.MEDIUM,
                Bucket.builder().addLimit(mediumPriorityLimit).build());

            // LOW priority: 200 req/min (background sync)
            Bandwidth lowPriorityLimit = Bandwidth.classic(
                200,
                Refill.intervally(200, REFILL_DURATION)
            );
            priorityBuckets.put(RequestPriority.LOW,
                Bucket.builder().addLimit(lowPriorityLimit).build());

            return new TenantBuckets(globalBucket, priorityBuckets);
        });
    }

    /**
     * Attempt to consume a token for a specific tenant with priority.
     *
     * @param tenantId Tenant ID
     * @param priority Request priority level
     * @return true if request is allowed, false if rate limit exceeded
     */
    public boolean tryConsume(Long tenantId, RequestPriority priority) {
        TenantBuckets buckets = getOrCreateBuckets(tenantId);

        // Check global limit for this tenant first
        if (!buckets.getGlobalBucket().tryConsume(1)) {
            log.warn("Rate limit exceeded for tenant {} (900 req/min)", tenantId);
            return false;
        }

        // Check priority-specific limit
        Bucket priorityBucket = buckets.getPriorityBuckets().get(priority);
        if (priorityBucket != null && !priorityBucket.tryConsume(1)) {
            log.warn("{} priority rate limit exceeded for tenant {}", priority, tenantId);
            // Refund global bucket token
            buckets.getGlobalBucket().addTokens(1);
            return false;
        }

        return true;
    }

    /**
     * Attempt to consume a token with HIGH priority (default for user requests)
     */
    public boolean tryConsume(Long tenantId) {
        return tryConsume(tenantId, RequestPriority.HIGH);
    }

    /**
     * Execute a request with rate limiting and retry logic.
     *
     * @param tenantId Tenant ID
     * @param priority Request priority
     * @param requestSupplier Lambda that executes the actual API call
     * @param maxRetries Maximum retry attempts
     * @return Result from requestSupplier
     * @throws RateLimitExceededException if rate limit exceeded after retries
     */
    public <T> T executeWithRateLimit(Long tenantId,
                                       RequestPriority priority,
                                       RequestSupplier<T> requestSupplier,
                                       int maxRetries) throws RateLimitExceededException {
        int attempts = 0;

        while (attempts < maxRetries) {
            if (tryConsume(tenantId, priority)) {
                try {
                    return requestSupplier.execute();
                } catch (Exception e) {
                    log.error("Error executing rate-limited request for tenant {}", tenantId, e);
                    throw new RuntimeException("Request execution failed for tenant " + tenantId, e);
                }
            }

            attempts++;

            if (attempts < maxRetries) {
                // Wait before retry (exponential backoff)
                long waitTimeMs = (long) Math.pow(2, attempts) * 100; // 200ms, 400ms, 800ms...
                try {
                    Thread.sleep(waitTimeMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Rate limit wait interrupted", e);
                }
            }
        }

        throw new RateLimitExceededException(
            "Rate limit exceeded for tenant " + tenantId + " after " + maxRetries + " attempts"
        );
    }

    /**
     * Execute with HIGH priority and 3 retries (default)
     */
    public <T> T executeWithRateLimit(Long tenantId, RequestSupplier<T> requestSupplier)
            throws RateLimitExceededException {
        return executeWithRateLimit(tenantId, RequestPriority.HIGH, requestSupplier, 3);
    }

    /**
     * Get available tokens for a tenant
     */
    public long getAvailableTokens(Long tenantId) {
        TenantBuckets buckets = getOrCreateBuckets(tenantId);
        return buckets.getGlobalBucket().getAvailableTokens();
    }

    /**
     * Get available tokens for a tenant with specific priority
     */
    public long getAvailableTokens(Long tenantId, RequestPriority priority) {
        TenantBuckets buckets = getOrCreateBuckets(tenantId);
        Bucket bucket = buckets.getPriorityBuckets().get(priority);
        return bucket != null ? bucket.getAvailableTokens() : 0;
    }

    /**
     * Clear rate limit buckets for a tenant (useful when tenant is deleted)
     */
    public void clearBuckets(Long tenantId) {
        tenantBucketsMap.remove(tenantId);
        log.info("Cleared rate limit buckets for tenant {}", tenantId);
    }

    /**
     * Request priority levels
     */
    public enum RequestPriority {
        HIGH,   // User-facing requests (searches, filters, detail views)
        MEDIUM, // WebSocket location updates
        LOW     // Background sync operations
    }

    /**
     * Functional interface for request execution
     */
    @FunctionalInterface
    public interface RequestSupplier<T> {
        T execute() throws Exception;
    }

    /**
     * Exception thrown when rate limit is exceeded
     */
    public static class RateLimitExceededException extends Exception {
        public RateLimitExceededException(String message) {
            super(message);
        }
    }
}
