package nc.ticagou.device;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Tablette appairée par la direction (ADR 0005). Seule l'empreinte SHA-256 de son secret est stockée ;
 * le code d'appairage à 6 chiffres est à usage unique et expire après 10 minutes.
 */
@Entity
@Table(name = "device")
public class Device {

    public enum State { EN_ATTENTE, APPAIREE, RETIREE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(nullable = false)
    private String name;

    @Column(name = "token_hash")
    private String tokenHash;

    @Column(name = "pairing_code_hash")
    private String pairingCodeHash;

    @Column(name = "pairing_expires_at")
    private Instant pairingExpiresAt;

    @Column(name = "paired_at")
    private Instant pairedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "failed_pin_attempts", nullable = false)
    private int failedPinAttempts;

    @Column(name = "pin_locked_until")
    private Instant pinLockedUntil;

    @Version
    private long version;

    protected Device() {
    }

    public Device(UUID structureId, String name, String createdBy, Instant createdAt) {
        this.structureId = structureId;
        this.name = name;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public State state() {
        if (revokedAt != null) {
            return State.RETIREE;
        }
        return tokenHash == null ? State.EN_ATTENTE : State.APPAIREE;
    }

    public void startPairing(String codeHash, Instant expiresAt) {
        this.pairingCodeHash = codeHash;
        this.pairingExpiresAt = expiresAt;
    }

    public boolean canPair(Instant now) {
        return revokedAt == null && pairingCodeHash != null && now.isBefore(pairingExpiresAt);
    }

    /** Appairage : un nouveau secret remplace l'ancien (tablette réinitialisée). */
    public void pair(String tokenHash, Instant now, String userAgent) {
        this.tokenHash = tokenHash;
        this.pairingCodeHash = null;
        this.pairingExpiresAt = null;
        this.pairedAt = now;
        this.lastSeenAt = now;
        this.userAgent = userAgent;
    }

    public boolean isActive() {
        return revokedAt == null && tokenHash != null;
    }

    public void seen(Instant now, String userAgent) {
        this.lastSeenAt = now;
        if (userAgent != null) {
            this.userAgent = userAgent;
        }
    }

    public void rename(String name) {
        this.name = name;
    }

    public void revoke(Instant now) {
        this.revokedAt = now;
        this.tokenHash = null;
        this.pairingCodeHash = null;
        this.pairingExpiresAt = null;
    }

    // --- PIN seul du pointage du personnel ------------------------------------------

    public boolean isPinLocked(Instant now) {
        return pinLockedUntil != null && now.isBefore(pinLockedUntil);
    }

    public void pinFailed(Instant now, int maxAttempts, Duration lockDuration) {
        failedPinAttempts++;
        if (failedPinAttempts >= maxAttempts) {
            pinLockedUntil = now.plus(lockDuration);
            failedPinAttempts = 0;
        }
    }

    public void pinSucceeded() {
        failedPinAttempts = 0;
        pinLockedUntil = null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public String getName() {
        return name;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getPairingExpiresAt() {
        return pairingExpiresAt;
    }

    public Instant getPairedAt() {
        return pairedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getPinLockedUntil() {
        return pinLockedUntil;
    }
}
