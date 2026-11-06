package nc.ticagou.staff;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Semaine type d'une personne, en vigueur à partir d'une date (l'historique est conservé). */
@Entity
@Table(name = "staff_schedule")
public class StaffSchedule {

    @Embeddable
    public record Day(
            @Column(name = "day_of_week", nullable = false) int dayOfWeek,
            @Column(name = "starts_at", nullable = false) LocalTime startsAt,
            @Column(name = "ends_at", nullable = false) LocalTime endsAt,
            @Column(name = "break_minutes", nullable = false) int breakMinutes,
            @Column(name = "section_id") UUID sectionId) {

        Shifts.DayTemplate toTemplate() {
            return new Shifts.DayTemplate(dayOfWeek, startsAt, endsAt, breakMinutes, sectionId);
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "staff_schedule_day", joinColumns = @JoinColumn(name = "schedule_id"))
    private List<Day> days = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected StaffSchedule() {
    }

    public StaffSchedule(UUID structureId, UUID userId, LocalDate effectiveFrom, Instant createdAt) {
        this.structureId = structureId;
        this.userId = userId;
        this.effectiveFrom = effectiveFrom;
        this.createdAt = createdAt;
    }

    public void replaceDays(List<Day> days) {
        this.days.clear();
        this.days.addAll(days);
    }

    public Shifts.Week toWeek() {
        return new Shifts.Week(effectiveFrom, days.stream().map(Day::toTemplate).toList());
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public List<Day> getDays() {
        return days;
    }
}
