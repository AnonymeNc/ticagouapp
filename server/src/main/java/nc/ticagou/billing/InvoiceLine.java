package nc.ticagou.billing;

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

import nc.ticagou.billing.InvoiceCalculator.Kind;
import nc.ticagou.billing.InvoiceCalculator.Line;

/** Ligne d'une facture émise (montant hors TGC ; les aides sont des lignes négatives). */
@Entity
@Immutable
@Table(name = "invoice_line")
public class InvoiceLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false)
    private UUID structureId;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(nullable = false)
    private int position;

    @Column(name = "child_id")
    private UUID childId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(nullable = false)
    private String label;

    private String detail;

    private String quantity;

    @Column(name = "unit_price")
    private Long unitPrice;

    @Column(nullable = false)
    private long amount;

    @Column(name = "tgc_rate", nullable = false)
    private int tgcRate;

    @Column(name = "tgc_amount", nullable = false)
    private long tgcAmount;

    @Column(name = "funder_id")
    private UUID funderId;

    protected InvoiceLine() {
    }

    public InvoiceLine(Invoice invoice, int position, Line line) {
        this.structureId = invoice.getStructureId();
        this.invoiceId = invoice.getId();
        this.position = position;
        this.childId = line.childId();
        this.kind = line.kind();
        this.label = line.label();
        this.detail = line.detail();
        this.quantity = line.quantity();
        this.unitPrice = line.unitPrice();
        this.amount = line.amount();
        this.tgcRate = line.tgcRate();
        this.tgcAmount = line.tgcAmount();
        this.funderId = line.funderId();
    }

    public UUID getId() {
        return id;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public int getPosition() {
        return position;
    }

    public UUID getChildId() {
        return childId;
    }

    public Kind getKind() {
        return kind;
    }

    public String getLabel() {
        return label;
    }

    public String getDetail() {
        return detail;
    }

    public String getQuantity() {
        return quantity;
    }

    public Long getUnitPrice() {
        return unitPrice;
    }

    public long getAmount() {
        return amount;
    }

    public int getTgcRate() {
        return tgcRate;
    }

    public long getTgcAmount() {
        return tgcAmount;
    }

    public UUID getFunderId() {
        return funderId;
    }

    public long total() {
        return amount + tgcAmount;
    }
}
