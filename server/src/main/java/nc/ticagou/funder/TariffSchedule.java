package nc.ticagou.funder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import nc.ticagou.contract.ForfaitCalculator;

/** Barème tarifaire daté : tarif horaire par tranche, semaines d'accueil par an, frais d'inscription. */
@Entity
@Table(name = "tariff_schedule")
public class TariffSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(nullable = false)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TariffCriterion criterion;

    @Column(name = "weeks_per_year", nullable = false)
    private int weeksPerYear;

    @Column(name = "registration_fee", nullable = false)
    private long registrationFee;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @ElementCollection
    @CollectionTable(name = "tariff_band", joinColumns = @JoinColumn(name = "schedule_id"))
    @OrderBy("lowerBound")
    private List<TariffBand> bands = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected TariffSchedule() {
    }

    public TariffSchedule(UUID structureId) {
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

    public void update(String label, TariffCriterion criterion, int weeksPerYear, long registrationFee,
                       LocalDate validFrom, LocalDate validTo, Collection<TariffBand> bands) {
        this.label = label;
        this.criterion = criterion;
        this.weeksPerYear = weeksPerYear;
        this.registrationFee = registrationFee;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.bands.clear();
        this.bands.addAll(bands.stream().sorted(Comparator.comparingLong(TariffBand::lowerBound)).toList());
    }

    public boolean isValidOn(LocalDate day) {
        return !validFrom.isAfter(day) && (validTo == null || !validTo.isBefore(day));
    }

    public boolean overlaps(LocalDate from, LocalDate to) {
        return (validTo == null || !validTo.isBefore(from)) && (to == null || !to.isBefore(validFrom));
    }

    public ForfaitCalculator.Tariff tariff() {
        return new ForfaitCalculator.Tariff(criterion, weeksPerYear,
                bands.stream().map(b -> new ForfaitCalculator.Band(b.lowerBound(), b.hourlyRate())).toList());
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

    public TariffCriterion getCriterion() {
        return criterion;
    }

    public int getWeeksPerYear() {
        return weeksPerYear;
    }

    public long getRegistrationFee() {
        return registrationFee;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public List<TariffBand> getBands() {
        return List.copyOf(bands);
    }

    public long getVersion() {
        return version;
    }
}
