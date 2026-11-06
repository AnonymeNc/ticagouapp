package nc.ticagou.staff;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Formation prévue ou suivie, avec son attestation chiffrée. */
@Entity
@Table(name = "staff_training")
public class StaffTraining {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private int minutes;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    private String organization;

    @Column(nullable = false)
    private boolean done;

    @Column(name = "file_id")
    private UUID fileId;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected StaffTraining() {
    }

    public StaffTraining(UUID structureId, UUID userId, Instant createdAt) {
        this.structureId = structureId;
        this.userId = userId;
        this.createdAt = createdAt;
    }

    public void update(String label, int minutes, LocalDate startsOn, LocalDate endsOn, String organization, boolean done) {
        this.label = label;
        this.minutes = minutes;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
        this.organization = organization;
        this.done = done;
    }

    public void attach(UUID fileId, String fileName) {
        this.fileId = fileId;
        this.fileName = fileName;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getLabel() {
        return label;
    }

    public int getMinutes() {
        return minutes;
    }

    public LocalDate getStartsOn() {
        return startsOn;
    }

    public LocalDate getEndsOn() {
        return endsOn;
    }

    public String getOrganization() {
        return organization;
    }

    public boolean isDone() {
        return done;
    }

    public UUID getFileId() {
        return fileId;
    }

    public String getFileName() {
        return fileName;
    }

    public long getVersion() {
        return version;
    }
}
