package nc.ticagou.billing;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import nc.ticagou.billing.InvoiceCalculator.Service;

/** Taux de TGC d'une prestation, daté (dixièmes de pour cent ; exonéré = 0). À confirmer par l'expert-comptable. */
@Entity
@Table(name = "tgc_rate")
public class TgcRate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Service service;

    @Column(nullable = false)
    private int rate;

    @Column(nullable = false)
    private boolean exempt;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Version
    private long version;

    protected TgcRate() {
    }

    public TgcRate(UUID structureId) {
        this.structureId = structureId;
    }

    public void update(Service service, int rate, boolean exempt, LocalDate validFrom, LocalDate validTo) {
        this.service = service;
        this.rate = exempt ? 0 : rate;
        this.exempt = exempt;
        this.validFrom = validFrom;
        this.validTo = validTo;
    }

    public boolean isValidOn(LocalDate day) {
        return !day.isBefore(validFrom) && (validTo == null || !day.isAfter(validTo));
    }

    public UUID getId() {
        return id;
    }

    public Service getService() {
        return service;
    }

    public int getRate() {
        return rate;
    }

    public boolean isExempt() {
        return exempt;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public long getVersion() {
        return version;
    }
}
