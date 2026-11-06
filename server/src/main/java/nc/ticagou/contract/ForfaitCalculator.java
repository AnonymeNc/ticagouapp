package nc.ticagou.contract;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import nc.ticagou.funder.TariffCriterion;

/**
 * Calcul du forfait mensuel d'un contrat d'accueil, en francs CFP entiers (aucune décimale).
 * <ul>
 *   <li>planning fixe : heures par semaine × semaines d'accueil par an ÷ 12 × tarif horaire, arrondi au franc ;</li>
 *   <li>planning variable : heures réservées par mois × tarif horaire.</li>
 * </ul>
 * Le tarif horaire est celui de la tranche du barème : la plus haute borne inférieure atteinte par le
 * quotient familial de la famille ou par le volume hebdomadaire d'accueil.
 */
public final class ForfaitCalculator {

    private static final int QUARTER = 15;

    private ForfaitCalculator() {
    }

    /** Jour du planning type, ISO-8601 (1 = lundi). */
    public record Slot(int dayOfWeek, LocalTime arrivesAt, LocalTime leavesAt) {

        int minutes() {
            return (int) Duration.between(arrivesAt, leavesAt).toMinutes();
        }
    }

    /** Tranche du barème : s'applique à partir de {@code lowerBound} (XPF ou minutes par semaine). */
    public record Band(long lowerBound, long hourlyRate) {
    }

    public record Tariff(TariffCriterion criterion, int weeksPerYear, List<Band> bands) {
    }

    public record Session(LocalDate date, LocalTime startsAt, LocalTime endsAt) {

        int minutes() {
            return (int) Duration.between(startsAt, endsAt).toMinutes();
        }
    }

    public enum AdaptationBilling { AU_TEMPS, PREMIERE_HEURE_OFFERTE, OFFERTE }

    /**
     * @param weeklyMinutes  volume hebdomadaire (équivalent hebdomadaire en planning variable)
     * @param monthlyMinutes heures facturées par mois, en minutes (tronquées à la minute pour l'affichage)
     */
    public record Quote(int weeklyMinutes, int monthlyMinutes, long bandLowerBound, long hourlyRate, long monthlyFee) {
    }

    public static int weeklyMinutes(Collection<Slot> slots) {
        return slots.stream().mapToInt(Slot::minutes).sum();
    }

    public static Quote fixed(Collection<Slot> slots, Tariff tariff, Long familyQuotient) {
        int weekly = weeklyMinutes(slots);
        var band = band(tariff, familyQuotient, weekly);
        long numerator = (long) weekly * tariff.weeksPerYear() * band.hourlyRate();
        int monthly = (int) ((long) weekly * tariff.weeksPerYear() / 12);
        return new Quote(weekly, monthly, band.lowerBound(), band.hourlyRate(), roundedDivision(numerator, 12 * 60));
    }

    public static Quote variable(int monthlyReservedMinutes, Tariff tariff, Long familyQuotient) {
        // Équivalent hebdomadaire pour choisir la tranche : heures du mois × 12 ÷ semaines d'accueil par an.
        int weekly = (int) roundedDivision((long) monthlyReservedMinutes * 12, tariff.weeksPerYear());
        var band = band(tariff, familyQuotient, weekly);
        return new Quote(weekly, monthlyReservedMinutes, band.lowerBound(), band.hourlyRate(),
                roundedDivision((long) monthlyReservedMinutes * band.hourlyRate(), 60));
    }

    /**
     * Tarif horaire des séances d'adaptation : celui de la tranche de la famille au quotient familial,
     * celui de la première tranche (accueil de moins de quelques heures par semaine) au volume horaire.
     */
    public static long adaptationRate(Tariff tariff, Long familyQuotient) {
        return band(tariff, familyQuotient, 0).hourlyRate();
    }

    public static int adaptationMinutes(Collection<Session> sessions) {
        return sessions.stream().mapToInt(Session::minutes).sum();
    }

    public static long adaptationCost(Collection<Session> sessions, AdaptationBilling billing, long hourlyRate) {
        int minutes = adaptationMinutes(sessions);
        int billed = switch (billing) {
            case AU_TEMPS -> minutes;
            case PREMIERE_HEURE_OFFERTE -> Math.max(0, minutes - 60);
            case OFFERTE -> 0;
        };
        return roundedDivision((long) billed * hourlyRate, 60);
    }

    static Band band(Tariff tariff, Long familyQuotient, int weeklyMinutes) {
        long value = switch (tariff.criterion()) {
            case QUOTIENT_FAMILIAL -> {
                if (familyQuotient == null) {
                    throw new IllegalArgumentException("Le quotient familial est requis pour ce barème.");
                }
                yield familyQuotient;
            }
            case HEURES_SEMAINE -> weeklyMinutes;
        };
        return tariff.bands().stream()
                .filter(b -> b.lowerBound() <= value)
                .max(Comparator.comparingLong(Band::lowerBound))
                .orElseThrow(() -> new IllegalArgumentException("Aucune tranche du barème ne couvre cette valeur."));
    }

    /**
     * Problèmes de saisie du planning : départ avant l'arrivée, horaires hors du quart d'heure,
     * jour fermé ou horaires hors de l'ouverture de la structure. Liste vide si tout est cohérent.
     *
     * @param opening horaires d'ouverture par jour ISO (un jour absent est fermé)
     */
    public static List<String> checkSlots(Collection<Slot> slots, Map<Integer, OpeningWindow> opening) {
        return slots.stream()
                .map(s -> checkSlot(s.dayOfWeek(), s.arrivesAt(), s.leavesAt(), opening))
                .flatMap(Optional::stream)
                .toList();
    }

    public static Optional<String> checkSlot(int dayOfWeek, LocalTime arrivesAt, LocalTime leavesAt,
                                             Map<Integer, OpeningWindow> opening) {
        var day = DAY_NAMES[dayOfWeek - 1];
        if (!leavesAt.isAfter(arrivesAt)) {
            return Optional.of(day + " : le départ doit suivre l’arrivée.");
        }
        if (arrivesAt.getMinute() % QUARTER != 0 || leavesAt.getMinute() % QUARTER != 0
                || arrivesAt.getSecond() != 0 || leavesAt.getSecond() != 0) {
            return Optional.of(day + " : les horaires se saisissent au quart d’heure.");
        }
        var window = opening.get(dayOfWeek);
        if (window == null) {
            return Optional.of(day + " : la structure est fermée ce jour-là.");
        }
        if (arrivesAt.isBefore(window.opensAt()) || leavesAt.isAfter(window.closesAt())) {
            return Optional.of("%s : la structure est ouverte de %s à %s.".formatted(day, window.opensAt(), window.closesAt()));
        }
        return Optional.empty();
    }

    public record OpeningWindow(LocalTime opensAt, LocalTime closesAt) {
    }

    static final String[] DAY_NAMES = { "Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi", "Dimanche" };

    /** Division entière arrondie au plus proche (demi vers le haut), pour des valeurs positives. */
    static long roundedDivision(long numerator, long denominator) {
        return (numerator * 2 + denominator) / (denominator * 2);
    }
}
