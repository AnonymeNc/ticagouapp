package nc.ticagou.billing;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Historique d'une facture : générée, notée envoyée, payée, relancée, avoir. */
@Entity
@Immutable
@Table(name = "invoice_event")
public class InvoiceEvent {

    public enum Kind { GENEREE, ENVOYEE, PAIEMENT, PAIEMENT_ANNULE, RELANCE, RELANCE_ENVOYEE, AVOIR }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false)
    private UUID structureId;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private String author;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected InvoiceEvent() {
    }

    public InvoiceEvent(Invoice invoice, Kind kind, String label, String author, Instant createdAt) {
        this.structureId = invoice.getStructureId();
        this.invoiceId = invoice.getId();
        this.kind = kind;
        this.label = label;
        this.author = author;
        this.createdAt = createdAt;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public Kind getKind() {
        return kind;
    }

    public String getLabel() {
        return label;
    }

    public String getAuthor() {
        return author;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
