package nc.ticagou.structure;

import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Horaires d'un jour d'ouverture ; un jour absent de la liste est un jour fermé. */
@Embeddable
public record OpeningHours(
        /* 1 = lundi … 7 = dimanche (ISO-8601) */
        @Column(name = "day_of_week", nullable = false) int dayOfWeek,
        @Column(name = "opens_at", nullable = false) LocalTime opensAt,
        @Column(name = "closes_at", nullable = false) LocalTime closesAt) {
}
