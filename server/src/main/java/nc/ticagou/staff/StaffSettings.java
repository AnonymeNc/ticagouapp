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

/** Paramètres du module Personnel, datés : délai d'alerte, pause par défaut, durée hebdomadaire de référence. */
@Entity
@Table(name = "staff_settings")
public class StaffSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "valid_from", nullable = false, updatable = false)
    private LocalDate validFrom;

    @Column(name = "alert_days", nullable = false)
    private int alertDays;

    @Column(name = "default_break_minutes", nullable = false)
    private int defaultBreakMinutes;

    @Column(name = "reference_weekly_minutes", nullable = false)
    private int referenceWeeklyMinutes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected StaffSettings() {
    }

    public StaffSettings(UUID structureId, LocalDate validFrom, Instant createdAt) {
        this.structureId = structureId;
        this.validFrom = validFrom;
        this.createdAt = createdAt;
    }

    public void update(int alertDays, int defaultBreakMinutes, int referenceWeeklyMinutes) {
        this.alertDays = alertDays;
        this.defaultBreakMinutes = defaultBreakMinutes;
        this.referenceWeeklyMinutes = referenceWeeklyMinutes;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public int getAlertDays() {
        return alertDays;
    }

    public int getDefaultBreakMinutes() {
        return defaultBreakMinutes;
    }

    public int getReferenceWeeklyMinutes() {
        return referenceWeeklyMinutes;
    }

    public long getVersion() {
        return version;
    }
}
