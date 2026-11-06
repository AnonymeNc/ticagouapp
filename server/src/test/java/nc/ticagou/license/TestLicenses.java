package nc.ticagou.license;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;

/** Abonnement de test, écrit comme le fait deploy/creche.sh. */
public final class TestLicenses {

    private TestLicenses() {
    }

    /** Tous les modules, 60 places. */
    public static void apply(JdbcTemplate jdbc, int tablets, LocalDate validUntil) {
        apply(jdbc, 60, EnumSet.allOf(LicenseModule.class), tablets, validUntil);
    }

    public static void apply(JdbcTemplate jdbc, int places, Set<LicenseModule> modules, int tablets, LocalDate validUntil) {
        jdbc.update("""
                INSERT INTO subscription (id, number, holder, plan, places, desktops, tablets, valid_until, modules)
                VALUES (1, 'TC-NC-2026-0482', 'Les P’tits Niaoulis', ?, ?, 2, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET plan = excluded.plan, places = excluded.places, tablets = excluded.tablets,
                    valid_until = excluded.valid_until, modules = excluded.modules""",
                "Structure — jusqu’à %d places".formatted(places), places, tablets, validUntil,
                modules.stream().map(Enum::name).collect(Collectors.joining(",")));
    }
}
