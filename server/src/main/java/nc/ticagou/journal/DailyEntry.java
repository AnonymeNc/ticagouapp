package nc.ticagou.journal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

/**
 * Transmission du jour : repas, sieste, change, activité, photo ou mot pour les parents. Une photo de groupe
 * concerne plusieurs enfants. {@code shared} : à montrer aux familles sur le portail (une photo ne l'est que sur
 * décision de l'éducatrice).
 */
@Entity
@Table(name = "daily_entry")
public class DailyEntry {

    public enum Kind { REPAS, SIESTE, CHANGE, ACTIVITE, PHOTO, VIDEO, MOT, MEDICAMENT;

        /** Photo ou vidéo : un fichier, l'autorisation de photographier pour la partager (ADR 0019). */
        public boolean isMedia() {
            return this == PHOTO || this == VIDEO;
        }
    }

    @Id
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(nullable = false)
    private LocalDate day;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Kind kind;

    private String text;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, String> details = new LinkedHashMap<>();

    @Column(name = "photo_file_id", updatable = false)
    private UUID photoFileId;

    @Column(nullable = false)
    private boolean shared;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "daily_entry_child", joinColumns = @JoinColumn(name = "entry_id"))
    @Column(name = "child_id")
    private Set<UUID> childIds = new LinkedHashSet<>();

    @Column(name = "author_user_id", updatable = false)
    private UUID authorUserId;

    @Column(name = "author_name", nullable = false, updatable = false)
    private String authorName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DailyEntry() {
    }

    public DailyEntry(UUID id, UUID structureId, LocalDate day, Instant occurredAt, Kind kind, Set<UUID> childIds,
                      UUID photoFileId, UUID authorUserId, String authorName, Instant now) {
        this.id = id;
        this.structureId = structureId;
        this.day = day;
        this.occurredAt = occurredAt;
        this.kind = kind;
        this.childIds = new LinkedHashSet<>(childIds);
        this.photoFileId = photoFileId;
        this.authorUserId = authorUserId;
        this.authorName = authorName;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(LocalDate day, Instant occurredAt, String text, Map<String, String> details, boolean shared,
                       Instant now) {
        this.day = day;
        this.occurredAt = occurredAt;
        this.text = text;
        this.details = new LinkedHashMap<>(details);
        this.shared = shared;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public LocalDate getDay() {
        return day;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Kind getKind() {
        return kind;
    }

    public String getText() {
        return text;
    }

    public Map<String, String> getDetails() {
        return details;
    }

    public UUID getPhotoFileId() {
        return photoFileId;
    }

    public boolean isShared() {
        return shared;
    }

    public Set<UUID> getChildIds() {
        return childIds;
    }

    public String getAuthorName() {
        return authorName;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
