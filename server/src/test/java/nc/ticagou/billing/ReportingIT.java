package nc.ticagou.billing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Statistiques d'un mois facturé : fréquentation, occupation, aides, facturation ; exports PDF et Excel. */
class ReportingIT extends BillingFixtures {

    String query;

    @BeforeEach
    void moisPointeEtFacture() throws Exception {
        var province = funder("Province Sud", "POURCENTAGE", 0, 200, 30_000L, 1);
        grant(lea, province, "Famille");
        passage(lea, TUESDAY, "07:30", "16:30");
        passage(tom, TUESDAY, "08:00", "16:00");
        passage(lea, TUESDAY.plusDays(1), "07:30", "15:30");
        generated();
        query = "year=" + MONTH.getYear() + "&month=" + MONTH.getMonthValue();
    }

    @Test
    void bilanDuMois() throws Exception {
        var report = body(getAs(director, "/api/v1/reporting?" + query));
        assertThat(report.get("capacity").asInt()).isEqualTo(48);
        assertThat(report.at("/total/children").asInt()).isEqualTo(2);
        assertThat(report.at("/total/realizedMinutes").asLong()).isEqualTo(540 + 480 + 480);
        assertThat(report.at("/total/plannedMinutes").asLong()).as("deux contrats 9 h par jour ouvré").isPositive();
        assertThat(report.at("/total/occupancy").asInt()).isPositive();
        assertThat(report.get("enrolled").asInt()).isEqualTo(2);
        var moyens = report.get("sections").findParents("name").stream()
                .filter(s -> s.get("name").asText().equals("Moyens")).findFirst().orElseThrow();
        assertThat(moyens.at("/indicators/realizedMinutes").asLong()).isEqualTo(1_500);
        assertThat(report.at("/funders/0/name").asText()).isEqualTo("Province Sud");
        assertThat(report.at("/funders/0/children").asInt()).isEqualTo(1);
        assertThat(report.at("/money/invoices").asInt()).isEqualTo(2);
        assertThat(report.at("/money/aids").asLong()).isEqualTo(report.at("/funders/0/amount").asLong());
        assertThat(report.get("months")).as("le détail par mois est réservé au bilan annuel").isEmpty();

        var year = body(getAs(director, "/api/v1/reporting?year=" + MONTH.getYear()));
        assertThat(year.get("label").asText()).isEqualTo("Année " + MONTH.getYear());
        assertThat(year.get("months").size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void exportsPdfEtExcel() throws Exception {
        var pdf = getAs(director, "/api/v1/reporting/pdf?" + query);
        assertThat(status(pdf)).isEqualTo(200);
        assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 4)).isEqualTo("%PDF");
        var xlsx = getAs(director, "/api/v1/reporting/xlsx?" + query);
        assertThat(status(xlsx)).isEqualTo(200);
        assertThat(xlsx.getResponse().getContentAsByteArray()[0]).as("classeur zip").isEqualTo((byte) 'P');
        assertThat(auditCount("REPORT_EXPORTED")).isEqualTo(2);
    }

    @Test
    void capaciteAgreeeDatee() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM structure_capacity", Integer.class)).isEqualTo(1);
        jdbc.update("UPDATE structure SET capacity = 50");
        assertThat(jdbc.queryForObject("SELECT capacity FROM structure_capacity ORDER BY valid_from DESC LIMIT 1", Integer.class))
                .isEqualTo(50);
    }

    @Test
    void reserveALaDirectionEtALaComptabilite() throws Exception {
        createUser(director, "EDUCATRICE", "lea.wema", "Educatrice-2026!", null);
        assertThat(status(getAs(accessToken("lea.wema", "Educatrice-2026!"), "/api/v1/reporting?" + query))).isEqualTo(403);
        var future = getAs(director, "/api/v1/reporting?year=" + (MONTH.getYear() + 2) + "&month=1");
        assertThat(body(future).get("code").asText()).isEqualTo("reporting_future");
    }
}
