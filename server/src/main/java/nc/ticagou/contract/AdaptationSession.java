package nc.ticagou.contract;

import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Séance d'adaptation, avant le début du contrat (« 09:00 – 10:00, avec un parent »). */
@Embeddable
public record AdaptationSession(
        @Column(name = "session_date", nullable = false) LocalDate sessionDate,
        @Column(name = "starts_at", nullable = false) LocalTime startsAt,
        @Column(name = "ends_at", nullable = false) LocalTime endsAt,
        @Column(name = "note") String note) {

    ForfaitCalculator.Session toSession() {
        return new ForfaitCalculator.Session(sessionDate, startsAt, endsAt);
    }
}
