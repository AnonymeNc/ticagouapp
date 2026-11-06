package nc.ticagou.billing;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Relance d'une facture impayée, niveau 1 à 3 ; la lettre s'imprime et son envoi est noté à la main. */
@Entity
@Table(name = "reminder")
public class Reminder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(nullable = false, updatable = false)
    private int level;

    @Column(name = "created_on", nullable = false, updatable = false)
    private LocalDate createdOn;

    @Column(name = "sent_on")
    private LocalDate sentOn;

    @Column(nullable = false, updatable = false)
    private String author;

    protected Reminder() {
    }

    public Reminder(UUID structureId, UUID invoiceId, int level, LocalDate createdOn, String author) {
        this.structureId = structureId;
        this.invoiceId = invoiceId;
        this.level = level;
        this.createdOn = createdOn;
        this.author = author;
    }

    public void markSent(LocalDate sentOn) {
        this.sentOn = sentOn;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public int getLevel() {
        return level;
    }

    public LocalDate getCreatedOn() {
        return createdOn;
    }

    public LocalDate getSentOn() {
        return sentOn;
    }

    public String getAuthor() {
        return author;
    }
}
