package es.hargos.ritrack.service;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import es.hargos.ritrack.entity.GlovoCredentialsEntity;
import es.hargos.ritrack.repository.GlovoCredentialsRepository;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Token Service for Multi-Tenant Glovo API Authentication.
 *
 * MULTI-TENANT ARCHITECTURE:
 * - Each tenant has its own Glovo API credentials (client_id, key_id, private_key_path)
 * - Each tenant gets its own OAuth2 access token
 * - Tokens are cached and auto-refreshed 60 seconds before expiry
 * - Thread-safe token management with locks per tenant
 *
 * Example:
 * - Tenant 1 (Arendel): Uses priv_arendel.pem → Gets token_arendel
 * - Tenant 2 (Entregalia): Uses priv_entregalia.pem → Gets token_entregalia
 * - Tokens do NOT interfere with each other
 */
@Service
public class TenantTokenService {

    private static final Logger log = LoggerFactory.getLogger(TenantTokenService.class);

    @Autowired
    private GlovoCredentialsRepository credentialsRepository;

    private final RestTemplate restTemplate = new RestTemplate();

    // Cache of tokens per tenant
    private final ConcurrentMap<Long, TenantTokenInfo> tenantTokens;

    public TenantTokenService() {
        this.tenantTokens = new ConcurrentHashMap<>();
    }

    /**
     * Token information for a tenant
     */
    @Data
    static class TenantTokenInfo {
        private String accessToken;
        private long expiryTime; // Unix timestamp in seconds
        private final ReentrantLock lock = new ReentrantLock();
    }

    /**
     * Get access token for a specific tenant.
     * Auto-refreshes if token is expired or about to expire (within 60 seconds).
     *
     * @param tenantId Tenant ID
     * @return Access token for Glovo API
     * @throws Exception if credentials not found or token refresh fails
     */
    public String getAccessToken(Long tenantId) throws Exception {
        TenantTokenInfo tokenInfo = tenantTokens.computeIfAbsent(
            tenantId,
            id -> new TenantTokenInfo()
        );

        long now = System.currentTimeMillis() / 1000;

        // Check if token needs refresh (null or expires within 60 seconds)
        if (tokenInfo.getAccessToken() == null || now >= tokenInfo.getExpiryTime() - 60) {
            // Lock per tenant (doesn't block other tenants)
            tokenInfo.lock.lock();
            try {
                // Double-check after acquiring lock
                if (tokenInfo.getAccessToken() == null || now >= tokenInfo.getExpiryTime() - 60) {
                    refreshToken(tenantId, tokenInfo);
                }
            } finally {
                tokenInfo.lock.unlock();
            }
        }

        return tokenInfo.getAccessToken();
    }

    /**
     * Refresh token for a tenant
     */
    private void refreshToken(Long tenantId, TenantTokenInfo tokenInfo) throws Exception {
        log.info("Refreshing token for tenant {}", tenantId);

        // 1. Get tenant's Glovo credentials from database
        GlovoCredentialsEntity credentials = credentialsRepository
            .findByTenantIdAndIsActive(tenantId, true)
            .orElseThrow(() -> new RuntimeException(
                "No active Glovo credentials found for tenant " + tenantId
            ));

        // 2. Generate JWT client assertion with tenant's credentials
        String clientAssertion = generateClientAssertion(credentials);

        // 3. Make OAuth2 token request to Glovo
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        String body = "grant_type=client_credentials" +
                "&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer" +
                "&client_assertion=" + clientAssertion;

        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.exchange(
            credentials.getTokenUrl(),
            HttpMethod.POST,
            entity,
            Map.class
        );

        if (response.getStatusCode().is2xxSuccessful()) {
            Map<String, Object> responseBody = response.getBody();
            tokenInfo.setAccessToken((String) responseBody.get("access_token"));

            Integer expiresIn = (Integer) responseBody.get("expires_in");
            tokenInfo.setExpiryTime((System.currentTimeMillis() / 1000) + expiresIn);

            log.info("Token refreshed successfully for tenant {} (expires in {} seconds)",
                tenantId, expiresIn);
        } else {
            throw new RuntimeException(
                "Failed to fetch token for tenant " + tenantId +
                ": " + response.getStatusCode()
            );
        }
    }

    /**
     * Generate JWT client assertion using tenant's credentials
     */
    private String generateClientAssertion(GlovoCredentialsEntity credentials) throws Exception {
        // Load tenant's private key
        RSAPrivateKey privateKey = loadPrivateKey(credentials.getPrivateKeyPath());

        // Create JWT signer
        JWSSigner signer = new RSASSASigner(privateKey);

        // Build JWT claims
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .audience(credentials.getAudienceUrl())
            .issuer(credentials.getClientId())
            .subject(credentials.getClientId())
            .jwtID(UUID.randomUUID().toString())
            .expirationTime(java.util.Date.from(now.plusSeconds(300))) // 5 minutes
            .build();

        // Build JWT header
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
            .keyID(credentials.getKeyId())
            .type(JOSEObjectType.JWT)
            .build();

        // Sign JWT
        SignedJWT signedJWT = new SignedJWT(header, claims);
        signedJWT.sign(signer);

        return signedJWT.serialize();
    }

    /**
     * Load RSA private key from PEM file
     */
    private RSAPrivateKey loadPrivateKey(String privateKeyPath) throws Exception {
        byte[] keyBytes = Files.readAllBytes(Path.of(privateKeyPath));

        String keyPem = new String(keyBytes)
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");

        byte[] decoded = Base64.getDecoder().decode(keyPem);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decoded);

        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(keySpec);
    }

    /**
     * Invalidate token for a tenant (useful when credentials change)
     */
    public void invalidateToken(Long tenantId) {
        tenantTokens.remove(tenantId);
        log.info("Token invalidated for tenant {}", tenantId);
    }

    /**
     * Get token expiry time for a tenant (for monitoring)
     */
    public Long getTokenExpiryTime(Long tenantId) {
        TenantTokenInfo tokenInfo = tenantTokens.get(tenantId);
        return tokenInfo != null ? tokenInfo.getExpiryTime() : null;
    }

    /**
     * Check if tenant has a valid token (for health checks)
     */
    public boolean hasValidToken(Long tenantId) {
        TenantTokenInfo tokenInfo = tenantTokens.get(tenantId);
        if (tokenInfo == null || tokenInfo.getAccessToken() == null) {
            return false;
        }

        long now = System.currentTimeMillis() / 1000;
        return now < tokenInfo.getExpiryTime();
    }
}
