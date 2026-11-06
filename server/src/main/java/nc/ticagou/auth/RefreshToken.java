package nc.ticagou.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Jeton de rafraîchissement : seul son empreinte SHA-256 est stockée. Usage unique ; tous les jetons
 * issus d'une même connexion partagent une famille, révoquée entière en cas de réutilisation.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_method", nullable = false, updatable = false)
    private AuthMethod authMethod;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected RefreshToken() {
    }

    RefreshToken(AppUser user, String tokenHash, UUID familyId, AuthMethod authMethod, Instant issuedAt,
                 Instant expiresAt) {
        this.structureId = user.getStructureId();
        this.userId = user.getId();
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.authMethod = authMethod;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    boolean isUsable(Instant now) {
        return usedAt == null && revokedAt == null && now.isBefore(expiresAt);
    }

    void markUsed(Instant now) {
        usedAt = now;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public AuthMethod getAuthMethod() {
        return authMethod;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
