package nc.ticagou.family;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Foyer : responsables, adresse, situation ; un ou plusieurs enfants inscrits. */
@Entity
@Table(name = "family")
public class Family {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    /** « Famille Wahéo » : sert aussi de phrase de confirmation pour la purge. */
    @Column(nullable = false)
    private String label;

    private String address;

    @Column(name = "postal_code")
    private String postalCode;

    private String city;

    private String situation;

    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Family() {
    }

    public Family(UUID structureId) {
        this.structureId = structureId;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void update(String label, String address, String postalCode, String city, String situation, String notes) {
        this.label = label;
        this.address = address;
        this.postalCode = postalCode;
        this.city = city;
        this.situation = situation;
        this.notes = notes;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public String getLabel() {
        return label;
    }

    public String getAddress() {
        return address;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getCity() {
        return city;
    }

    public String getSituation() {
        return situation;
    }

    public String getNotes() {
        return notes;
    }

    public long getVersion() {
        return version;
    }
}
