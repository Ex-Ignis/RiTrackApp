package es.hargos.ritrack.security;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import es.hargos.ritrack.context.TenantContext;
import es.hargos.ritrack.dto.JwtTenantInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Utility class for validating JWT tokens from HargosAuthSystem.
 * Extracts tenant information and validates token signature and expiration.
 */
@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    @Value("${jwt.secret}")
    private String jwtSecret;

    /**
     * Validate JWT token from HargosAuthSystem
     * @param token JWT token string
     * @return true if valid, false otherwise
     */
    public boolean validateToken(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            JWSVerifier verifier = new MACVerifier(jwtSecret.getBytes());

            // Verify signature
            if (!signedJWT.verify(verifier)) {
                log.warn("JWT signature verification failed");
                return false;
            }

            // Check expiration
            Date expirationTime = signedJWT.getJWTClaimsSet().getExpirationTime();
            if (expirationTime == null || expirationTime.before(new Date())) {
                log.warn("JWT token expired");
                return false;
            }

            return true;
        } catch (Exception e) {
            log.error("Error validating JWT token", e);
            return false;
        }
    }

    /**
     * Extract tenant information from JWT token and create TenantContext.TenantInfo
     * @param token JWT token string
     * @return TenantInfo with user and tenant data, or null if invalid
     */
    public TenantContext.TenantInfo extractTenantInfo(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

            // Extract basic user info
            Long userId = claims.getLongClaim("userId");
            String email = claims.getStringClaim("email");

            // Extract tenants array from JWT
            List<Map<String, Object>> tenants = (List<Map<String, Object>>) claims.getClaim("tenants");

            if (tenants == null || tenants.isEmpty()) {
                log.warn("No tenants found in JWT for user {}", userId);
                return null;
            }

            // Filter tenants for RiTrack app only
            List<Long> tenantIds = new ArrayList<>();
            List<String> tenantNames = new ArrayList<>();
            List<String> roles = new ArrayList<>();

            for (Map<String, Object> tenant : tenants) {
                String appName = (String) tenant.get("appName");
                if ("RiTrack".equalsIgnoreCase(appName)) {
                    // Parse tenant ID (might be Integer or Long)
                    Object tenantIdObj = tenant.get("tenantId");
                    Long tenantId = tenantIdObj instanceof Integer
                            ? ((Integer) tenantIdObj).longValue()
                            : (Long) tenantIdObj;

                    tenantIds.add(tenantId);
                    tenantNames.add((String) tenant.get("tenantName"));
                    roles.add((String) tenant.get("role"));
                }
            }

            if (tenantIds.isEmpty()) {
                log.warn("User {} has no RiTrack tenants", userId);
                return null;
            }

            return TenantContext.TenantInfo.builder()
                    .userId(userId)
                    .email(email)
                    .tenantIds(tenantIds)
                    .tenantNames(tenantNames)
                    .roles(roles)
                    .schemaNames(new ArrayList<>()) // Will be populated by service layer
                    .build();

        } catch (Exception e) {
            log.error("Error extracting tenant info from JWT", e);
            return null;
        }
    }

    /**
     * Extract email from token (without full validation)
     */
    public String getEmailFromToken(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            return signedJWT.getJWTClaimsSet().getSubject();
        } catch (Exception e) {
            log.error("Error extracting email from JWT", e);
            return null;
        }
    }

    /**
     * Extract user ID from token (without full validation)
     */
    public Long getUserIdFromToken(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            return signedJWT.getJWTClaimsSet().getLongClaim("userId");
        } catch (Exception e) {
            log.error("Error extracting user ID from JWT", e);
            return null;
        }
    }

    /**
     * Extract global role from JWT token (ej: SUPER_ADMIN).
     * Este rol es global y no está asociado a ningún tenant.
     *
     * Busca el rol en dos ubicaciones:
     * 1. Claim "role" en nivel superior del JWT
     * 2. tenants[0].role si tenants[0].appName = "SYSTEM"
     *
     * @param token JWT token string
     * @return Global role or null if not present
     */
    public String extractGlobalRole(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

            // Opción 1: Buscar claim "role" en nivel superior
            String globalRole = claims.getStringClaim("role");
            if (globalRole != null) {
                return globalRole;
            }

            // Opción 2: Buscar en tenants[0] si appName = "SYSTEM"
            List<Map<String, Object>> tenants = (List<Map<String, Object>>) claims.getClaim("tenants");
            if (tenants != null && !tenants.isEmpty()) {
                Map<String, Object> firstTenant = tenants.get(0);
                String appName = (String) firstTenant.get("appName");

                if ("SYSTEM".equalsIgnoreCase(appName)) {
                    return (String) firstTenant.get("role");
                }
            }

            return null;

        } catch (Exception e) {
            log.debug("No global role found in JWT");
            return null;
        }
    }

    /**
     * Extract tenants from JWT token as a list of JwtTenantInfo objects.
     * Filters only tenants with appName "riders" or "RiTrack".
     *
     * @param token JWT token string
     * @return List of JwtTenantInfo, empty if no tenants found
     */
    public List<JwtTenantInfo> extractTenants(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

            // Extract tenants array from JWT
            List<Map<String, Object>> tenantsRaw = (List<Map<String, Object>>) claims.getClaim("tenants");

            if (tenantsRaw == null || tenantsRaw.isEmpty()) {
                log.warn("No tenants found in JWT");
                return new ArrayList<>();
            }

            // Convert to JwtTenantInfo and filter for RiTrack app
            return tenantsRaw.stream()
                    .filter(t -> {
                        String appName = (String) t.get("appName");
                        return "RiTrack".equalsIgnoreCase(appName);
                    })
                    .map(t -> {
                        Object tenantIdObj = t.get("tenantId");
                        Long tenantId = tenantIdObj instanceof Integer
                                ? ((Integer) tenantIdObj).longValue()
                                : (Long) tenantIdObj;

                        return new JwtTenantInfo(
                                tenantId,
                                (String) t.get("tenantName"),
                                (String) t.get("appName"),
                                (String) t.get("role")
                        );
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Error extracting tenants from JWT", e);
            return new ArrayList<>();
        }
    }

    /**
     * Extract all claims from JWT token
     */
    public JWTClaimsSet extractAllClaims(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            return signedJWT.getJWTClaimsSet();
        } catch (Exception e) {
            log.error("Error extracting claims from JWT", e);
            return null;
        }
    }

    /**
     * MULTI-TENANT: Extrae un tenantId específico de la lista de tenants en el JWT
     * y verifica que el usuario tiene acceso a ese tenant.
     *
     * @param token JWT token
     * @param tenantId El tenant ID que el usuario quiere acceder (hargosTenantId)
     * @return El tenantId si el usuario tiene acceso, null si no
     */
    public Long extractTenantIdIfAuthorized(String token, Long tenantId) {
        try {
            JWTClaimsSet claims = extractAllClaims(token);
            if (claims == null) {
                return null;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tenants = (List<Map<String, Object>>) claims.getClaim("tenants");

            if (tenants == null || tenants.isEmpty()) {
                log.warn("No tenants found in JWT");
                return null;
            }

            // Verificar que el usuario tiene acceso a este tenant
            boolean hasAccess = tenants.stream()
                    .anyMatch(t -> {
                        String appName = (String) t.get("appName");
                        if (!"RiTrack".equalsIgnoreCase(appName)) {
                            return false;
                        }

                        Object tid = t.get("tenantId");
                        Long tenantIdInJwt = tid instanceof Integer
                                ? ((Integer) tid).longValue()
                                : (Long) tid;

                        return tenantIdInJwt != null && tenantIdInJwt.equals(tenantId);
                    });

            if (!hasAccess) {
                log.warn("User attempted to access tenant {} which is not in their JWT", tenantId);
                return null;
            }

            return tenantId;

        } catch (Exception e) {
            log.error("Error validating tenant access in JWT", e);
            return null;
        }
    }

    /**
     * MULTI-TENANT: Extrae el primer tenantId del JWT (fallback para compatibilidad).
     * Filtra solo tenants de RiTrack.
     *
     * @param token JWT token string
     * @return El primer tenantId de RiTrack, o null si no hay ninguno
     */
    public Long extractFirstTenantId(String token) {
        try {
            JWTClaimsSet claims = extractAllClaims(token);
            if (claims == null) {
                return null;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tenants = (List<Map<String, Object>>) claims.getClaim("tenants");

            if (tenants == null || tenants.isEmpty()) {
                log.warn("No tenants found in JWT");
                return null;
            }

            // Buscar el primer tenant de RiTrack
            for (Map<String, Object> tenant : tenants) {
                String appName = (String) tenant.get("appName");
                if ("RiTrack".equalsIgnoreCase(appName)) {
                    Object tenantIdObj = tenant.get("tenantId");
                    Long tenantId = tenantIdObj instanceof Integer
                            ? ((Integer) tenantIdObj).longValue()
                            : (Long) tenantIdObj;

                    log.debug("Using first RiTrack tenant from JWT: {}", tenantId);
                    return tenantId;
                }
            }

            log.warn("No RiTrack tenants found in JWT");
            return null;

        } catch (Exception e) {
            log.error("Error extracting first tenant ID from JWT", e);
            return null;
        }
    }
}
