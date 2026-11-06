package nc.ticagou.contract;

import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Jour du planning type d'une version de contrat, ISO-8601 (1 = lundi). */
@Embeddable
public record ScheduleDay(
        @Column(name = "day_of_week", nullable = false) int dayOfWeek,
        @Column(name = "arrives_at", nullable = false) LocalTime arrivesAt,
        @Column(name = "leaves_at", nullable = false) LocalTime leavesAt) {

    ForfaitCalculator.Slot toSlot() {
        return new ForfaitCalculator.Slot(dayOfWeek, arrivesAt, leavesAt);
    }
}
