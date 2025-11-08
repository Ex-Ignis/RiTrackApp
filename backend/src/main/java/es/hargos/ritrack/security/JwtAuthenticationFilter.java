package es.hargos.ritrack.security;

import es.hargos.ritrack.context.TenantContext;
import es.hargos.ritrack.dto.JwtTenantInfo;
import es.hargos.ritrack.entity.TenantEntity;
import es.hargos.ritrack.repository.TenantRepository;
import es.hargos.ritrack.service.TenantService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JWT Authentication Filter - Validates JWT tokens from HargosAuthSystem
 * and populates TenantContext for multi-tenant data access.
 *
 * Flow:
 * 1. Extract JWT token from Authorization header
 * 2. Validate token signature and expiration
 * 3. Extract tenant information
 * 4. Map tenant IDs to schema names
 * 5. Store in TenantContext (ThreadLocal)
 * 6. Set Spring Security authentication
 * 7. Clear context after request completes
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private TenantService tenantService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String jwt = extractJwtFromRequest(request);

            if (jwt != null && jwtUtil.validateToken(jwt)) {
                authenticateUser(jwt, request);
            } else {
                log.debug("No valid JWT token found in request to {}", request.getRequestURI());
            }
        } catch (Exception e) {
            log.error("Cannot set user authentication", e);
        } finally {
            try {
                filterChain.doFilter(request, response);
            } finally {
                // CRITICAL: Clear TenantContext after request to prevent memory leaks
                TenantContext.clear();
            }
        }
    }

    /**
     * Extract JWT token from Authorization header
     */
    private String extractJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        
        return null;
    }

    /**
     * Authenticate user and populate TenantContext.
     *
     * Flow:
     * 1. Check if user has global role (SUPER_ADMIN)
     * 2. If SUPER_ADMIN, authenticate without tenants
     * 3. Otherwise, extract tenants from JWT
     * 4. Read X-Tenant-Id header from request
     * 5. Validate header value is in JWT's tenant list
     * 6. Find or create tenant in RiTrack
     * 7. Set TenantContext with selected tenant
     */
    private void authenticateUser(String jwt, HttpServletRequest request) {
        try {
            // 1. Check for global role (SUPER_ADMIN)
            String globalRole = jwtUtil.extractGlobalRole(jwt);

            if ("SUPER_ADMIN".equals(globalRole)) {
                setupSuperAdminContext(jwt, request);
                return;
            }

            // 2. Extract tenants from JWT (for normal users)
            List<JwtTenantInfo> jwtTenants = jwtUtil.extractTenants(jwt);

            if (jwtTenants.isEmpty()) {
                log.warn("No RiTrack tenants found in JWT and user is not SUPER_ADMIN");
                return;
            }

            // 2. Read X-Tenant-Id header
            String tenantIdHeader = request.getHeader("X-Tenant-Id");

            if (tenantIdHeader == null || tenantIdHeader.trim().isEmpty()) {
                log.debug("No X-Tenant-Id header found - using first tenant from JWT");
                // Use first tenant by default
                JwtTenantInfo firstTenant = jwtTenants.get(0);
                setupTenantContext(firstTenant, jwt, request);
                return;
            }

            // 3. Validate X-Tenant-Id is in JWT
            Long requestedTenantId;
            try {
                requestedTenantId = Long.parseLong(tenantIdHeader);
            } catch (NumberFormatException e) {
                log.error("Invalid X-Tenant-Id header format: {}", tenantIdHeader);
                return;
            }

            Long finalRequestedTenantId = requestedTenantId;
            JwtTenantInfo selectedTenant = jwtTenants.stream()
                    .filter(t -> t.getTenantId().equals(finalRequestedTenantId))
                    .findFirst()
                    .orElse(null);

            if (selectedTenant == null) {
                log.error("User attempted to access tenant {} which is not in their JWT. Available tenants: {}",
                        requestedTenantId,
                        jwtTenants.stream().map(JwtTenantInfo::getTenantId).collect(Collectors.toList()));
                return;
            }

            // 4 & 5. Setup tenant context
            setupTenantContext(selectedTenant, jwt, request);

        } catch (Exception e) {
            log.error("Error during authentication", e);
        }
    }

    /**
     * Setup authentication for SUPER_ADMIN (no tenant context needed)
     */
    private void setupSuperAdminContext(String jwt, HttpServletRequest request) {
        // Extract user info from JWT
        Long userId = jwtUtil.getUserIdFromToken(jwt);
        String email = jwtUtil.getEmailFromToken(jwt);

        // Set Spring Security authentication with SUPER_ADMIN role
        List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")
        );

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                email,
                null,
                authorities
        );
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        log.debug("SUPER_ADMIN {} authenticated without tenant context", email);
    }

    /**
     * Setup TenantContext and Spring Security authentication
     */
    private void setupTenantContext(JwtTenantInfo jwtTenant, String jwt, HttpServletRequest request) {
        // Find or create tenant in RiTrack
        TenantEntity tenant = tenantService.findOrCreateByHargosTenantId(
                jwtTenant.getTenantId(),
                jwtTenant.getTenantName()
        );

        // Extract user info from JWT
        Long userId = jwtUtil.getUserIdFromToken(jwt);
        String email = jwtUtil.getEmailFromToken(jwt);

        // Create TenantContext
        TenantContext.TenantInfo contextInfo = TenantContext.TenantInfo.builder()
                .userId(userId)
                .email(email)
                .tenantIds(Collections.singletonList(tenant.getId()))  // Internal RiTrack ID
                .tenantNames(Collections.singletonList(tenant.getName()))
                .schemaNames(Collections.singletonList(tenant.getSchemaName()))
                .roles(Collections.singletonList(jwtTenant.getRole()))
                .build();

        TenantContext.setCurrentContext(contextInfo);

        // Set Spring Security authentication
        List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                new SimpleGrantedAuthority("ROLE_" + jwtTenant.getRole())
        );

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                email,
                null,
                authorities
        );
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        log.debug("User {} authenticated for tenant '{}' (RiTrack ID: {}, HargosAuth ID: {})",
                email, tenant.getName(), tenant.getId(), tenant.getHargosTenantId());
    }

    /**
     * Skip filter for public endpoints
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();

        // Public endpoints that don't require authentication
        return path.startsWith("/actuator") ||
               path.startsWith("/error") ||
               path.equals("/");
    }
}
