package nc.ticagou.license;

import java.time.LocalDate;

import org.springframework.jdbc.core.JdbcTemplate;

/** Abonnement de test, écrit comme le fait deploy/creche.sh. Une seule formule, tout compris (ADR 0021). */
public final class TestLicenses {

    private TestLicenses() {
    }

    /** 60 places. */
    public static void apply(JdbcTemplate jdbc, int tablets, LocalDate validUntil) {
        apply(jdbc, 60, tablets, validUntil);
    }

    public static void apply(JdbcTemplate jdbc, int places, int tablets, LocalDate validUntil) {
        jdbc.update("""
                INSERT INTO subscription (id, number, holder, plan, places, desktops, tablets, valid_until)
                VALUES (1, 'TC-NC-2026-0482', 'Les P’tits Niaoulis', ?, ?, 2, ?, ?)
                ON CONFLICT (id) DO UPDATE SET plan = excluded.plan, places = excluded.places, tablets = excluded.tablets,
                    valid_until = excluded.valid_until""",
                "Ti’Cagou — jusqu’à %d places".formatted(places), places, tablets, validUntil);
    }
}
