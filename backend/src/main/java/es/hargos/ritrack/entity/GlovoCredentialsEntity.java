package es.hargos.ritrack.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Glovo API credentials per tenant.
 * Stores OAuth2 JWT client credentials for authentication with Glovo APIs.
 */
@Entity
@Table(name = "glovo_credentials", schema = "public")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlovoCredentialsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, unique = true)
    private TenantEntity tenant;

    // OAuth2 JWT Client Credentials
    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "key_id", nullable = false)
    private String keyId;

    /**
     * Path to private key file for JWT signing (e.g., "./keys/arendel_private.pem")
     * The actual private key should NEVER be stored in the database.
     */
    @Column(name = "private_key_path", nullable = false, length = 500)
    private String privateKeyPath;

    // OAuth2 endpoints
    @Column(name = "audience_url", nullable = false, length = 500)
    private String audienceUrl = "https://sts.deliveryhero.io";

    @Column(name = "token_url", nullable = false, length = 500)
    private String tokenUrl = "https://sts.dh-auth.io/oauth2/token";

    // API base URLs
    @Column(name = "rooster_base_url", nullable = false, length = 500)
    private String roosterBaseUrl = "https://gv-es.usehurrier.com/api/rooster";

    @Column(name = "live_base_url", nullable = false, length = 500)
    private String liveBaseUrl = "https://gv-es.usehurrier.com/api/rider-live-operations";

    // Glovo company and contract IDs
    @Column(name = "company_id", nullable = false)
    private Integer companyId;

    @Column(name = "contract_id", nullable = false)
    private Integer contractId;

    // Status and validation
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "is_validated", nullable = false)
    private Boolean isValidated = false;

    @Column(name = "last_validated_at")
    private LocalDateTime lastValidatedAt;

    @Column(name = "validation_error", columnDefinition = "TEXT")
    private String validationError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
