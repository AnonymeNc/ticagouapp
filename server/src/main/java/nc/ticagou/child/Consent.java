package nc.ticagou.child;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** Autorisation parentale signée : photos, sorties, soins d'urgence. */
@Entity
@Table(name = "consent")
public class Consent {

    public enum Type { PHOTOS, SORTIES, SOINS_URGENCE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "child_id", nullable = false, updatable = false)
    private UUID childId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Type type;

    @Column(nullable = false)
    private boolean granted;

    /** Portée précise (« usage interne, pas de diffusion en ligne »…). */
    private String details;

    @Column(name = "signed_on")
    private LocalDate signedOn;

    @Column(name = "signed_by")
    private String signedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Consent() {
    }

    public Consent(UUID structureId, UUID childId, Type type) {
        this.structureId = structureId;
        this.childId = childId;
        this.type = type;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public void update(boolean granted, String details, LocalDate signedOn, String signedBy) {
        this.granted = granted;
        this.details = details;
        this.signedOn = signedOn;
        this.signedBy = signedBy;
    }

    public Type getType() {
        return type;
    }

    public boolean isGranted() {
        return granted;
    }

    public String getDetails() {
        return details;
    }

    public LocalDate getSignedOn() {
        return signedOn;
    }

    public String getSignedBy() {
        return signedBy;
    }
}
