package nc.ticagou.license;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.common.ApiException;
import nc.ticagou.config.TiCagouProperties;
import nc.ticagou.license.LicenseTerms.State;

/**
 * Abonnement de la crèche (ADR 0013) : une ligne écrite par l'éditeur (deploy/creche.sh), effet immédiat. Les règles de
 * dates (évaluation, renouvellement, grâce, lecture seule) sont dans {@link LicenseTerms} (ADR 0011). L'abonnement
 * plafonne la capacité agréée, le nombre de tablettes et de PC ({@link DesktopClientService}) ; échue, elle passe en
 * lecture seule ({@link LicenseGuard}). Une seule formule : tout est compris (ADR 0021).
 */
@Service
public class LicenseService {

    /**
     * @param deadline       jour où la saisie sera bloquée (fin de l'évaluation ou de la grâce), nul si la licence court
     * @param daysLeft       jours avant {@code deadline}, ou avant l'échéance si la licence est à renouveler
     * @param desktops       PC du bureau qui occupent une place ; {@code desktopLimit} PC permis
     */
    public record LicenseDto(State state, boolean readOnly, License license, LocalDate renewalFrom, LocalDate deadline,
                             long daysLeft, int pairedTablets, int desktops, int desktopLimit, int sections,
                             int capacity, String version, String message) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String version;
    private final LicenseTerms terms;
    private final Duration desktopIdle;

    public LicenseService(JdbcTemplate jdbc, Clock clock, ObjectProvider<BuildProperties> build,
                          TiCagouProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        var info = build.getIfAvailable();
        this.version = info != null ? info.getVersion() : "dev";
        this.terms = new LicenseTerms(properties.license().evaluationDays(), properties.license().graceDays());
        this.desktopIdle = properties.license().desktopIdle();
    }

    @Transactional
    public LicenseDto current() {
        var license = stored();
        var status = status(license);
        int tablets = jdbc.queryForObject("SELECT count(*) FROM device WHERE token_hash IS NOT NULL AND revoked_at IS NULL", Integer.class);
        int capacity = jdbc.queryForObject("SELECT coalesce(sum(capacity), 0) FROM structure", Integer.class);
        int sections = jdbc.queryForObject("SELECT count(*) FROM section", Integer.class);
        int desktops = jdbc.queryForObject("SELECT count(*) FROM desktop_client WHERE revoked_at IS NULL AND last_seen_at > ?",
                Integer.class, Timestamp.from(clock.instant().minus(desktopIdle)));
        var renewalFrom = license == null ? null : license.validUntil().minusDays(LicenseTerms.RENEWAL_DAYS);
        return new LicenseDto(status.state(), status.state().readOnly(), license, renewalFrom, status.deadline(),
                status.daysLeft(), tablets, desktops, desktopLimit(license), sections, capacity, version, status.message());
    }

    /** État du jour, sans les compteurs : lu à chaque modification ({@link LicenseGuard}). */
    public LicenseTerms.Status status() {
        return status(stored());
    }

    /** PC du bureau permis : ceux de la licence, ou {@value LicenseTerms#EVALUATION_DESKTOPS} en évaluation. */
    public int desktopLimit() {
        return desktopLimit(stored());
    }

    private static int desktopLimit(License license) {
        return license == null ? LicenseTerms.EVALUATION_DESKTOPS : Math.max(license.desktops(), 1);
    }

    /** Dernier jour où la saisie est permise ; c'est aussi la fin de l'accès des parents au portail. */
    public LocalDate lastWritableDay() {
        var today = today();
        return terms.lastWritableDay(installedOn(today), stored());
    }

    private LicenseTerms.Status status(License license) {
        var today = today();
        return terms.status(installedOn(today), today, license);
    }

    private LocalDate installedOn(LocalDate today) {
        var installed = jdbc.queryForList("SELECT initialized_at FROM installation WHERE id = 1", Timestamp.class);
        return installed.isEmpty() || installed.getFirst() == null ? today
                : LocalDate.ofInstant(installed.getFirst().toInstant(), clock.getZone());
    }

    /** Dernier jour enregistré : reculer l'horloge du PC ne rend pas de jours d'évaluation ou de grâce. */
    private volatile LocalDate lastRecorded;

    LocalDate today() {
        var now = LocalDate.now(clock);
        var recorded = lastRecorded;
        if (recorded != null && !now.isAfter(recorded)) {
            return recorded;
        }
        var seen = jdbc.queryForList("SELECT last_seen_on FROM installation WHERE id = 1", java.sql.Date.class);
        if (seen.isEmpty()) {
            return now;
        }
        var stored = seen.getFirst() == null ? null : seen.getFirst().toLocalDate();
        if (stored != null && !now.isAfter(stored)) {
            lastRecorded = stored;
            return stored;
        }
        jdbc.update("UPDATE installation SET last_seen_on = ? WHERE id = 1 AND (last_seen_on IS NULL OR last_seen_on < ?)",
                java.sql.Date.valueOf(now), java.sql.Date.valueOf(now));
        lastRecorded = now;
        return now;
    }

    /** Appairage d'une tablette : refusé si la licence a expiré ou si toutes les tablettes autorisées sont prises. */
    public void requireTabletSlot() {
        var dto = current();
        if (dto.state().expired() || dto.readOnly()) {
            throw ApiException.rule("license_expired", dto.message());
        }
        if (dto.license() == null) {
            return;
        }
        if (dto.pairedTablets() >= dto.license().tablets()) {
            throw ApiException.rule("license_tablets",
                    "Votre abonnement autorise %d tablettes, toutes appairées : retirez-en une ou contactez Ti’Cagou."
                            .formatted(dto.license().tablets()));
        }
    }

    /** Capacité agréée demandée : refusée au-delà des places de l'abonnement (sans abonnement, pas de limite). */
    public void requireCapacity(int capacity) {
        var license = stored();
        if (license != null && capacity > license.places()) {
            throw ApiException.rule("license_places", "Votre abonnement couvre %d places : contactez Ti’Cagou avant d’augmenter la capacité agréée."
                    .formatted(license.places()));
        }
    }

    License stored() {
        return jdbc.query("SELECT * FROM subscription WHERE id = 1", (rs, n) -> new License(rs.getString("number"),
                rs.getString("holder"), rs.getString("plan"), rs.getInt("places"),
                rs.getInt("desktops"), rs.getInt("tablets"), rs.getObject("valid_until", LocalDate.class)))
                .stream().findFirst().orElse(null);
    }
}
