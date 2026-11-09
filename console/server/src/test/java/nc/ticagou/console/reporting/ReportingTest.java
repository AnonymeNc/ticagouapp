package nc.ticagou.console.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import nc.ticagou.console.reporting.Reporting.Bucket;

/** Calculs du reporting : bornes de la balance âgée, période en mois, coût du support, taux de départ, CSV. */
class ReportingTest {

    @Test
    void balanceAgeeAuxBornes() {
        assertThat(Reporting.bucket(-5)).isEqualTo(Bucket.NON_ECHU);
        assertThat(Reporting.bucket(0)).as("échue aujourd'hui : pas en retard").isEqualTo(Bucket.NON_ECHU);
        assertThat(Reporting.bucket(1)).isEqualTo(Bucket.J30);
        assertThat(Reporting.bucket(30)).isEqualTo(Bucket.J30);
        assertThat(Reporting.bucket(31)).isEqualTo(Bucket.J60);
        assertThat(Reporting.bucket(60)).isEqualTo(Bucket.J60);
        assertThat(Reporting.bucket(61)).isEqualTo(Bucket.J90);
        assertThat(Reporting.bucket(90)).isEqualTo(Bucket.J90);
        assertThat(Reporting.bucket(91)).isEqualTo(Bucket.PLUS_90);
        assertThat(Reporting.daysLate(LocalDate.of(2026, 12, 8), LocalDate.of(2027, 3, 9))).isEqualTo(91);
        assertThat(Reporting.daysLate(null, LocalDate.of(2027, 3, 9))).isZero();
    }

    @Test
    void periodeEnMoisEtCoutDuSupport() {
        assertThat(Reporting.months(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 11, 9))).isEqualTo(11);
        assertThat(Reporting.months(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 9))).isEqualTo(1);
        assertThat(Reporting.months(LocalDate.of(2026, 12, 15), LocalDate.of(2027, 1, 14))).isEqualTo(2);
        assertThat(Reporting.supportCost(90, 1, 9_000)).isEqualTo(13_500);
        assertThat(Reporting.supportCost(45, 11, 9_000)).as("613,6 arrondi au franc").isEqualTo(614);
        assertThat(Reporting.supportCost(0, 3, 9_000)).isZero();
    }

    @Test
    void tauxDeDepart() {
        assertThat(Reporting.churnTenths(0, 0)).isZero();
        assertThat(Reporting.churnTenths(1, 0)).as("pas de base").isNull();
        assertThat(Reporting.churnTenths(1, 8)).as("12,5 %").isEqualTo(125);
        assertThat(Reporting.churnTenths(0, 9)).isZero();
    }

    @Test
    void csvPointVirguleBomEtEchappement() {
        var bytes = ReportingExports.csv(List.of("A", "B"), List.of(List.of("Crèche « Tiaré »; Nouméa", "dit \"oui\""), List.of("x", "")));
        var text = new String(bytes, StandardCharsets.UTF_8);
        assertThat(text).isEqualTo("﻿A;B\r\n\"Crèche « Tiaré »; Nouméa\";\"dit \"\"oui\"\"\"\r\nx;\r\n");
    }
}
