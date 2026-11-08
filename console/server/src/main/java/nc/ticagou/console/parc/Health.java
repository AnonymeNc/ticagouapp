package nc.ticagou.console.parc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pastille d'une crèche dans le parc (maquette « Console éditeur ») :
 * <ul>
 *   <li>{@code OFF} (gris) : prospect sans instance, contrat résilié ou instance arrêtée ;</li>
 *   <li>{@code BAD} (rouge) : alerte de supervision ouverte, conteneur en échec ;</li>
 *   <li>{@code WARN} (orange) : à surveiller — PC ou tablettes au-delà du contrat, version en retard, état de supervision
 *       ou rapport de l'hôte manquant ;</li>
 *   <li>{@code OK} (vert) : en ordre.</li>
 * </ul>
 * Les impayés s'ajouteront à l'orange avec la facturation (phase 3).
 */
public record Health(Level level, List<String> reasons) {

    public enum Level { OK, WARN, BAD, OFF }

    /** Conteneur arrêté ou disparu, d'après l'hôte. */
    static final Set<String> STOPPED = Set.of("exited", "created", "dead", "paused", "absent");

    /**
     * @param lifecycle          PROSPECT, CLIENT ou RESILIE
     * @param supervisionSeenAt  dernier état reçu par la supervision ; nul s'il n'y en a jamais eu
     * @param status             dernier état de supervision (MonitoringStatus de l'instance)
     * @param host               dernière ligne du rapport de l'hôte pour cette crèche
     * @param openAlerts         libellés des alertes ouvertes
     * @param latestVersion      version la plus récente du parc
     * @param hostReporting      l'hôte envoie bien ses rapports (sinon, son absence n'est pas reprochée à la crèche)
     */
    record Input(String lifecycle, Instant supervisionSeenAt, boolean supervisionSuspended, JsonNode status, JsonNode host,
                 List<String> openAlerts, String latestVersion, boolean hostReporting) {
    }

    static Health evaluate(Input in) {
        if ("PROSPECT".equals(in.lifecycle())) {
            return new Health(Level.OFF, List.of("Prospect : pas encore d’instance"));
        }
        if ("RESILIE".equals(in.lifecycle())) {
            return new Health(Level.OFF, List.of("Contrat résilié"));
        }
        var container = in.host() == null ? null : in.host().path("container").asText(null);
        if (container != null && STOPPED.contains(container)) {
            return new Health(Level.OFF, List.of("absent".equals(container) ? "Instance absente de l’hôte" : "Instance arrêtée"));
        }

        var bad = new ArrayList<String>(in.openAlerts());
        if ("unhealthy".equals(container)) {
            bad.add("Conteneur en échec");
        }
        if (!bad.isEmpty()) {
            return new Health(Level.BAD, bad);
        }

        var warn = new ArrayList<String>();
        var status = in.status();
        if (in.supervisionSeenAt() == null || status == null || status.isNull() || status.isMissingNode()) {
            warn.add("Aucun état de supervision reçu");
        } else {
            if (in.supervisionSuspended()) {
                warn.add("Supervision suspendue");
            }
            var license = status.path("license");
            int desktops = license.path("desktops").asInt(0);
            int desktopLimit = license.path("desktopLimit").asInt(0);
            if (desktopLimit > 0 && desktops > desktopLimit) {
                warn.add("PC au-delà du contrat (%d/%d)".formatted(desktops, desktopLimit));
            }
            var tabletLimit = in.host() == null ? null : in.host().path("subscription").path("tablets");
            int paired = status.path("tablets").path("paired").asInt(0);
            if (tabletLimit != null && tabletLimit.isNumber() && paired > tabletLimit.asInt()) {
                warn.add("Tablettes au-delà du contrat (%d/%d)".formatted(paired, tabletLimit.asInt()));
            }
            var version = status.path("version").asText(null);
            if (in.latestVersion() != null && Versions.readable(version) && Versions.compare(version, in.latestVersion()) < 0) {
                warn.add("Version en retard (%s, courante %s)".formatted(version, in.latestVersion()));
            }
        }
        if (in.hostReporting() && in.host() == null) {
            warn.add("Absente du rapport de l’hôte");
        }
        return warn.isEmpty() ? new Health(Level.OK, List.of()) : new Health(Level.WARN, warn);
    }
}
