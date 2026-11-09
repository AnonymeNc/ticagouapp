package nc.ticagou.portal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Actualité de la crèche publiée sur le portail parents : toute la crèche ou une section, avec une pièce jointe
 * facultative (menu de la semaine, projet pédagogique, affiche). Une actualité ne se modifie pas : on la retire et on
 * en publie une autre.
 */
@Entity
@Table(name = "structure_news")
public class StructureNews {

    @Id
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "section_id", updatable = false)
    private UUID sectionId;

    @Column(nullable = false, updatable = false)
    private String title;

    @Column(nullable = false, updatable = false)
    private String body;

    @Column(name = "file_id", updatable = false)
    private UUID fileId;

    @Column(name = "file_name", updatable = false)
    private String fileName;

    @Column(name = "published_by", nullable = false, updatable = false)
    private String publishedBy;

    @Column(name = "published_at", nullable = false, updatable = false)
    private Instant publishedAt;

    @Column(name = "expires_on", updatable = false)
    private LocalDate expiresOn;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Column(name = "withdrawn_by")
    private String withdrawnBy;

    protected StructureNews() {
    }

    public StructureNews(UUID id, UUID structureId, UUID sectionId, String title, String body, UUID fileId,
                         String fileName, String publishedBy, Instant publishedAt, LocalDate expiresOn) {
        this.id = id;
        this.structureId = structureId;
        this.sectionId = sectionId;
        this.title = title;
        this.body = body;
        this.fileId = fileId;
        this.fileName = fileName;
        this.publishedBy = publishedBy;
        this.publishedAt = publishedAt;
        this.expiresOn = expiresOn;
    }

    public void withdraw(String by, Instant at) {
        this.withdrawnBy = by;
        this.withdrawnAt = at;
    }

    /** Visible des parents ce jour-là : ni retirée, ni expirée. */
    public boolean isVisibleOn(LocalDate day) {
        return withdrawnAt == null && (expiresOn == null || !expiresOn.isBefore(day));
    }

    public UUID getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public UUID getSectionId() {
        return sectionId;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public UUID getFileId() {
        return fileId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getPublishedBy() {
        return publishedBy;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public LocalDate getExpiresOn() {
        return expiresOn;
    }

    public Instant getWithdrawnAt() {
        return withdrawnAt;
    }

    public String getWithdrawnBy() {
        return withdrawnBy;
    }
}
