package nc.ticagou.funder;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Tranche d'un barème, appliquée à partir de sa borne inférieure (XPF de quotient ou minutes par semaine). */
@Embeddable
public record TariffBand(
        @Column(name = "lower_bound", nullable = false) long lowerBound,
        @Column(name = "hourly_rate", nullable = false) long hourlyRate) {
}
