package nc.ticagou.portail.supervision;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Alertes de supervision d'un serveur de crèche, à partir de son dernier état (ADR 0012) :
 * <ul>
 *   <li>SILENCE : aucun état reçu depuis 2 h entre 6 h et 19 h (heure de Nouméa), 12 h la nuit ;</li>
 *   <li>SAUVEGARDE : pas de sauvegarde réussie depuis 48 h, sauvegardes désactivées ou outils absents ;</li>
 *   <li>DISQUE : moins de 10 % ou de 5 Go libres sur le disque des données ou celui des sauvegardes ;</li>
 *   <li>LICENCE : à renouveler (moins de 30 jours), expirée ou en lecture seule ;</li>
 *   <li>TABLETTES : une tablette appairée muette depuis 3 jours ;</li>
 *   <li>PORTAIL : 5 échecs de synchronisation du portail parents à la suite.</li>
 * </ul>
 */
public final class AlertRules {

    static final ZoneId NOUMEA = ZoneId.of("Pacific/Noumea");
    static final long MIN_FREE_BYTES = 5L * 1024 * 1024 * 1024;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy à HH:mm").withZone(NOUMEA);

    private AlertRules() {
    }

    public static String label(String kind) {
        return switch (kind) {
            case "SILENCE" -> "Serveur muet";
            case "SAUVEGARDE" -> "Sauvegarde";
            case "DISQUE" -> "Espace disque";
            case "LICENCE" -> "Licence";
            case "TABLETTES" -> "Tablettes";
            case "PORTAIL" -> "Portail parents";
            default -> kind;
        };
    }

    /** Alertes en cours : type → explication. */
    public static Map<String, String> evaluate(JsonNode status, Instant lastSeen, Instant now) {
        var alerts = new LinkedHashMap<String, String>();
        if (lastSeen != null) {
            int hour = now.atZone(NOUMEA).getHour();
            var limit = hour >= 6 && hour < 19 ? Duration.ofHours(2) : Duration.ofHours(12);
            if (Duration.between(lastSeen, now).compareTo(limit) > 0) {
                alerts.put("SILENCE", "Aucune nouvelle du serveur depuis le %s : PC éteint, réseau ou internet coupé ?"
                        .formatted(DATE_TIME.format(lastSeen)));
            }
        }
        if (status == null || status.isMissingNode() || status.isNull()) {
            return alerts;
        }
        var backup = status.path("backup");
        if (!backup.path("enabled").asBoolean(true)) {
            alerts.put("SAUVEGARDE", "Les sauvegardes automatiques sont désactivées.");
        } else if (!backup.path("toolsAvailable").asBoolean(true)) {
            alerts.put("SAUVEGARDE", "Outils de sauvegarde PostgreSQL introuvables sur le serveur.");
        } else if (backup.path("stale").asBoolean(false)) {
            var last = backup.path("lastSuccessAt").asText(null);
            alerts.put("SAUVEGARDE", last == null || last.isBlank() ? "Aucune sauvegarde réussie."
                    : "Dernière sauvegarde réussie le %s.".formatted(DATE_TIME.format(Instant.parse(last))));
        }
        var disk = status.path("disk");
        var low = lowDisk("données", disk.path("dataFreeBytes"), disk.path("dataTotalBytes"));
        if (low == null) {
            low = lowDisk("sauvegardes", disk.path("backupFreeBytes"), disk.path("backupTotalBytes"));
        }
        if (low != null) {
            alerts.put("DISQUE", low);
        }
        var license = status.path("license");
        var state = license.path("state").asText("");
        switch (state) {
            case "A_RENOUVELER" -> alerts.put("LICENCE", "Licence à renouveler avant le %s.".formatted(license.path("validUntil").asText("?")));
            case "EXPIREE_GRACE" -> alerts.put("LICENCE", "Licence expirée : lecture seule le %s.".formatted(license.path("deadline").asText("?")));
            case "LECTURE_SEULE", "EVALUATION_TERMINEE" -> alerts.put("LICENCE", "Ti’Cagou est en lecture seule (licence).");
            default -> { }
        }
        int silent = status.path("tablets").path("silent").asInt(0);
        if (silent > 0) {
            alerts.put("TABLETTES", "%d tablette%s muette%s depuis 3 jours.".formatted(silent, silent > 1 ? "s" : "", silent > 1 ? "s" : ""));
        }
        var portal = status.path("portal");
        if ("ACTIF".equals(portal.path("state").asText()) && portal.path("failures").asInt(0) >= 5) {
            alerts.put("PORTAIL", "Synchronisation du portail parents en échec : " + portal.path("lastError").asText("erreur inconnue"));
        }
        return alerts;
    }

    private static String lowDisk(String which, JsonNode free, JsonNode total) {
        if (!free.isNumber()) {
            return null;
        }
        long f = free.asLong();
        long t = total.asLong(0);
        if (f < MIN_FREE_BYTES || (t > 0 && f * 10 < t)) {
            return "Disque des %s presque plein : %s libres%s.".formatted(which, gigabytes(f), t > 0 ? " sur " + gigabytes(t) : "");
        }
        return null;
    }

    static String gigabytes(long bytes) {
        return String.format(java.util.Locale.FRANCE, "%.1f Go", bytes / 1024.0 / 1024 / 1024);
    }
}
