package nc.ticagou.staff;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** État d'une qualification à une date : sans limite, valide, à renouveler (sous le délai d'alerte), expirée. */
public enum QualificationState {
    SANS_LIMITE, VALIDE, A_RENOUVELER, EXPIREE;

    public static QualificationState of(LocalDate validUntil, LocalDate today, int alertDays) {
        if (validUntil == null) {
            return SANS_LIMITE;
        }
        if (validUntil.isBefore(today)) {
            return EXPIREE;
        }
        return ChronoUnit.DAYS.between(today, validUntil) <= alertDays ? A_RENOUVELER : VALIDE;
    }

    public boolean alert() {
        return this == A_RENOUVELER || this == EXPIREE;
    }
}
