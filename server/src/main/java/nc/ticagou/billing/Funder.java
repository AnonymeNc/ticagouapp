package nc.ticagou.billing;

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
import jakarta.persistence.Version;

import nc.ticagou.billing.InvoiceCalculator.Calculation;
import nc.ticagou.billing.InvoiceCalculator.RateBase;

/**
 * Financeur (province, organisme, employeur conventionné, commune). Une aide aux familles est déduite des factures
 * puis réclamée au financeur ; une subvention à la structure n'apparaît pas sur les factures.
 */
@Entity
@Table(name = "funder")
public class Funder {

    public enum Kind { AIDE_FAMILLE, SUBVENTION_STRUCTURE }

    public enum Category { PROVINCE, ORGANISME, EMPLOYEUR, COMMUNE, AUTRE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Calculation calculation;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private int rate;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_base", nullable = false)
    private RateBase rateBase = RateBase.FRAIS_GARDE;

    @Column(name = "monthly_cap")
    private Long monthlyCap;

    @Column(nullable = false)
    private int priority;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "convention_ref")
    private String conventionRef;

    private String contact;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Funder() {
    }

    public Funder(UUID structureId) {
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

    public void update(String name, Kind kind, Category category, Calculation calculation, long amount, int rate,
                       RateBase rateBase, Long monthlyCap, int priority, LocalDate validFrom, LocalDate validTo,
                       String conventionRef, String contact) {
        this.name = name;
        this.kind = kind;
        this.category = category;
        this.calculation = calculation;
        this.amount = amount;
        this.rate = rate;
        this.rateBase = rateBase;
        this.monthlyCap = monthlyCap;
        this.priority = priority;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.conventionRef = conventionRef;
        this.contact = contact;
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

    public Kind getKind() {
        return kind;
    }

    public Category getCategory() {
        return category;
    }

    public Calculation getCalculation() {
        return calculation;
    }

    public long getAmount() {
        return amount;
    }

    public int getRate() {
        return rate;
    }

    public RateBase getRateBase() {
        return rateBase;
    }

    public Long getMonthlyCap() {
        return monthlyCap;
    }

    public int getPriority() {
        return priority;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public String getConventionRef() {
        return conventionRef;
    }

    public String getContact() {
        return contact;
    }

    public long getVersion() {
        return version;
    }
}
