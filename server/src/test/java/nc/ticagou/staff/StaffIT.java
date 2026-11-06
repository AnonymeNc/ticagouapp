package nc.ticagou.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;

import com.fasterxml.jackson.databind.JsonNode;

import nc.ticagou.IntegrationTest;

class StaffIT extends IntegrationTest {

    static final ZoneId NOUMEA = ZoneId.of("Pacific/Noumea");
    static final LocalDate TODAY = LocalDate.now(NOUMEA);
    static final YearMonth LAST_MONTH = YearMonth.from(TODAY).minusMonths(1);
    static final LocalDate NEXT_MONDAY = TODAY.with(TemporalAdjusters.next(DayOfWeek.MONDAY));

    String director;
    String bebes;
    String celine;
    String vaimiti;

    @BeforeEach
    void team() throws Exception {
        director = install();
        bebes = sectionId(director, "Bébés");
        celine = createUser(director, "EDUCATRICE", "celine.hnawia", "Celine-2026!!", "4811").get("id").asText();
        vaimiti = createUser(director, "EDUCATRICE", "vaimiti.t", "Vaimiti-2026!!", "4812").get("id").asText();
    }

    static Map<String, Object> day(int day, String from, String to, String sectionId) {
        var d = new LinkedHashMap<String, Object>();
        d.put("dayOfWeek", day);
        d.put("startsAt", from);
        d.put("endsAt", to);
        d.put("breakMinutes", 30);
        d.put("sectionId", sectionId);
        return d;
    }

    /** Semaine de Céline (maquette) : 39 h. */
    void celineWeek(LocalDate from) throws Exception {
        var result = perform(put("/api/v1/staff/" + celine + "/schedule"), director, Map.of("effectiveFrom", from.toString(),
                "days", List.of(day(1, "06:30", "15:00", bebes), day(2, "06:30", "15:00", bebes), day(3, "10:00", "18:30", bebes),
                        day(4, "06:30", "15:00", bebes), day(5, "10:30", "18:00", bebes))));
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(result).get("weekMinutes").asInt()).isEqualTo(39 * 60);
    }

    Map<String, Object> absence(String userId, String kind, LocalDate from, LocalDate to) {
        var a = new LinkedHashMap<String, Object>();
        a.put("userId", userId);
        a.put("kind", kind);
        a.put("fromDay", from.toString());
        a.put("toDay", to.toString());
        a.put("halfDay", false);
        return a;
    }

    @Test
    void ficheQualificationsEtAlertes() throws Exception {
        var profile = new LinkedHashMap<String, Object>();
        profile.put("birthDate", "1991-03-12");
        profile.put("phone", "77 12 34");
        profile.put("diploma", "DE d’auxiliaire de puériculture");
        profile.put("contractType", "CDI");
        profile.put("weeklyMinutes", 39 * 60);
        profile.put("hiredOn", "2021-02-01");
        var updated = perform(put("/api/v1/staff/" + celine + "/profile"), director, profile);
        assertThat(status(updated)).as(updated.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(updated).at("/member/diploma").asText()).isEqualTo("DE d’auxiliaire de puériculture");

        var sst = body(perform(post("/api/v1/staff/" + celine + "/qualifications"), director, Map.of("kind", "SST",
                "label", "Sauveteur secouriste du travail (SST)", "obtainedOn", TODAY.minusYears(2).toString(),
                "validUntil", TODAY.plusDays(14).toString(), "verified", true)));
        assertThat(sst.get("state").asText()).isEqualTo("A_RENOUVELER");
        perform(post("/api/v1/staff/" + vaimiti + "/qualifications"), director, Map.of("kind", "HACCP",
                "label", "Hygiène alimentaire (HACCP)", "validUntil", TODAY.minusDays(3).toString(), "verified", false));
        perform(post("/api/v1/staff/" + celine + "/trainings"), director, Map.of("label", "Recyclage SST", "minutes", 420,
                "startsOn", TODAY.plusDays(9).toString(), "endsOn", TODAY.plusDays(9).toString(), "done", false));

        var upload = multipart(HttpMethod.PUT, "/api/v1/staff/qualifications/" + sst.get("id").asText() + "/file")
                .file(new MockMultipartFile("file", "sst-2024.pdf", "application/pdf", "%PDF-1.4 attestation".getBytes(StandardCharsets.UTF_8)));
        var attached = perform(upload, director, null);
        assertThat(status(attached)).as(attached.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(attached).get("fileName").asText()).isEqualTo("sst-2024.pdf");
        var file = getAs(director, "/api/v1/staff/qualifications/" + sst.get("id").asText() + "/file");
        assertThat(new String(file.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8)).startsWith("%PDF");

        var team = body(getAs(director, "/api/v1/staff"));
        assertThat(team.get("people").asInt()).isEqualTo(3);
        assertThat(team.get("expired").asInt()).isEqualTo(1);
        assertThat(team.get("toRenew").asInt()).isEqualTo(1);
        assertThat(team.get("nextDue").asText()).isEqualTo(TODAY.plusDays(14).toString());
        assertThat(team.get("plannedTrainings").asInt()).isEqualTo(1);
        assertThat(team.get("alertDays").asInt()).isEqualTo(30);
        var alerts = body(getAs(director, "/api/v1/staff/alerts"));
        assertThat(alerts).extracting(a -> a.get("state").asText()).containsExactly("EXPIREE", "A_RENOUVELER");

        var fiche = body(getAs(director, "/api/v1/staff/" + celine));
        assertThat(fiche.get("phone").asText()).isEqualTo("77 12 34");
        assertThat(fiche.get("qualifications")).hasSize(1);
        assertThat(fiche.get("trainings")).hasSize(1);
        assertThat(auditCount("STAFF_RECORD_VIEWED")).isEqualTo(1);

        perform(put("/api/v1/staff/settings"), director, Map.of("alertDays", 10, "defaultBreakMinutes", 30,
                "referenceWeeklyMinutes", 39 * 60));
        assertThat(body(getAs(director, "/api/v1/staff")).get("toRenew").asInt()).as("alerte réglée à 10 jours").isZero();
    }

    @Test
    void encadrementPrevisionnelParCreneau() throws Exception {
        createVolumeTariff(director, "2020-01-01");
        for (int i = 0; i < 6; i++) {
            var child = createChild(director, "Bébé" + i, "Famille" + i, TODAY.minusMonths(8).toString(), bebes,
                    TODAY.minusDays(1).toString());
            signedContract(child, TODAY);
        }
        celineWeek(TODAY);
        var v = perform(put("/api/v1/staff/" + vaimiti + "/schedule"), director, Map.of("effectiveFrom", TODAY.toString(),
                "days", List.of(day(1, "08:00", "17:30", bebes))));
        assertThat(status(v)).isEqualTo(200);

        var forecast = body(getAs(director, "/api/v1/staff/forecast?day=" + NEXT_MONDAY));
        assertThat(forecast.get("open").asBoolean()).isTrue();
        assertThat(slot(forecast, "06:30").at("/sections/" + bebes + "/status").asText()).isEqualTo("CONFORME");
        assertThat(slot(forecast, "07:30").at("/sections/" + bebes + "/status").asText()).isEqualTo("INSUFFISANT");
        assertThat(slot(forecast, "07:30").at("/sections/" + bebes + "/required").asInt()).isEqualTo(2);
        assertThat(slot(forecast, "09:00").at("/sections/" + bebes + "/status").asText()).isEqualTo("CONFORME");
        assertThat(slot(forecast, "15:00").at("/sections/" + bebes + "/status").asText()).as("Céline part à 15:00")
                .isEqualTo("INSUFFISANT");
        assertThat(forecast.get("shortages").asInt()).as("07:30, puis 15:00 à 16:30").isEqualTo(4);

        // Céline reste jusqu'à 17:00 ce lundi : il ne manque plus que 07:30 – 08:00.
        var change = perform(put("/api/v1/staff/" + celine + "/shifts/" + NEXT_MONDAY), director, Map.of("startsAt", "06:30",
                "endsAt", "17:00", "breakMinutes", 60, "sectionId", bebes, "note", "Réunion de parents"));
        assertThat(body(change).get("overridden").asBoolean()).isTrue();
        assertThat(body(getAs(director, "/api/v1/staff/forecast?day=" + NEXT_MONDAY)).get("shortages").asInt()).isEqualTo(1);

        var planning = body(getAs(director, "/api/v1/staff/planning?week=" + NEXT_MONDAY));
        assertThat(planning.at("/shortages/" + NEXT_MONDAY).asInt()).isEqualTo(1);
        var row = rowOf(planning, celine);
        assertThat(row.at("/shifts/0/note").asText()).isEqualTo("Réunion de parents");

        perform(post("/api/v1/staff/absences"), director, absence(vaimiti, "MALADIE", NEXT_MONDAY, NEXT_MONDAY));
        assertThat(body(getAs(director, "/api/v1/staff/forecast?day=" + NEXT_MONDAY)).get("shortages").asInt())
                .as("Vaimiti absente : sous-effectif toute la journée avec des enfants").isGreaterThan(10);
        assertThat(status(perform(delete("/api/v1/staff/" + celine + "/shifts/" + NEXT_MONDAY), director, null))).isEqualTo(200);
    }

    @Test
    void feuilleDHeuresValidationEtExports() throws Exception {
        celineWeek(LAST_MONTH.minusMonths(1).atDay(1));
        var tuesday = LAST_MONTH.atDay(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.TUESDAY)).plusWeeks(1);

        var sheet = body(getAs(director, "/api/v1/staff/timesheets/" + LAST_MONTH));
        var person = personOf(sheet, celine);
        assertThat(person.get("anomalies").asInt()).as("jours prévus sans pointage").isGreaterThan(15);
        var refused = perform(post("/api/v1/staff/timesheets/" + LAST_MONTH + "/validate"), director, null);
        assertThat(body(refused).get("code").asText()).isEqualTo("timesheet_anomalies");
        assertThat(body(perform(post("/api/v1/staff/timesheets/" + LAST_MONTH + "/validate"), director, null)).get("code").asText())
                .isEqualTo("timesheet_anomalies");

        perform(post("/api/v1/staff/absences"), director, absence(celine, "CONGES", LAST_MONTH.atDay(1), tuesday.minusDays(1)));
        var second = absence(celine, "MALADIE", tuesday.plusDays(1), LAST_MONTH.atEndOfMonth());
        assertThat(status(perform(post("/api/v1/staff/absences"), director, second))).isEqualTo(201);
        var overlap = perform(post("/api/v1/staff/absences"), director, absence(celine, "AUTRE", tuesday.plusDays(1), tuesday.plusDays(2)));
        assertThat(body(overlap).get("code").asText()).isEqualTo("absence_overlap");
        var record = new LinkedHashMap<String, Object>();
        record.put("userId", celine);
        record.put("arrivedAt", tuesday.atTime(LocalTime.of(6, 30)).atZone(NOUMEA).toInstant().toString());
        record.put("departedAt", tuesday.atTime(LocalTime.of(17, 0)).atZone(NOUMEA).toInstant().toString());
        record.put("sectionId", bebes);
        record.put("reason", "Pointage de test");
        assertThat(status(perform(post("/api/v1/attendance/staff/records"), director, record))).isEqualTo(201);

        person = personOf(body(getAs(director, "/api/v1/staff/timesheets/" + LAST_MONTH)), celine);
        assertThat(person.get("anomalies").asInt()).isZero();
        assertThat(person.get("workedMinutes").asInt()).as("10 h 30 pointées, pause de 30 min").isEqualTo(10 * 60);
        assertThat(person.at("/absenceMinutes/CONGES").asInt() + person.at("/absenceMinutes/MALADIE").asInt())
                .isEqualTo(person.get("plannedMinutes").asInt() - 8 * 60);

        var validated = body(perform(post("/api/v1/staff/timesheets/" + LAST_MONTH + "/validate"), director, null));
        assertThat(validated.get("validated").asBoolean()).isTrue();
        var locked = perform(put("/api/v1/staff/" + celine + "/shifts/" + tuesday), director,
                Map.of("startsAt", "07:00", "endsAt", "12:00", "breakMinutes", 0));
        assertThat(body(locked).get("code").asText()).isEqualTo("timesheet_validated");

        var csv = getAs(director, "/api/v1/staff/timesheets/" + LAST_MONTH + "/export.csv");
        assertThat(status(csv)).isEqualTo(200);
        var text = new String(csv.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(text).contains("Personne;Fonction;Contrat;Date").contains("PrénomEDUCATRICE NomEDUCATRICE").contains("10,00");
        var xlsx = getAs(director, "/api/v1/staff/timesheets/" + LAST_MONTH + "/export.xlsx");
        assertThat(xlsx.getResponse().getContentAsByteArray()).startsWith((byte) 'P', (byte) 'K');
        assertThat(auditCount("TIMESHEET_EXPORTED")).isEqualTo(2);

        var reopened = perform(post("/api/v1/staff/timesheets/" + LAST_MONTH + "/reopen"), director,
                Map.of("reason", "Erreur sur une absence"));
        assertThat(body(reopened).get("validated").asBoolean()).isFalse();
        assertThat(status(getAs(director, "/api/v1/staff/timesheets/" + LAST_MONTH + "/export.csv"))).isEqualTo(422);
    }

    @Test
    void droitsParRole() throws Exception {
        celineWeek(TODAY.minusMonths(2));
        createUser(director, "COMPTABILITE", "compta", "Comptable-2026!", "4821");
        var educ = accessToken("celine.hnawia", "Celine-2026!!");
        var compta = accessToken("compta", "Comptable-2026!");

        assertThat(status(getAs(educ, "/api/v1/staff"))).isEqualTo(403);
        assertThat(status(getAs(educ, "/api/v1/staff/" + vaimiti))).isEqualTo(403);
        var mine = body(getAs(educ, "/api/v1/staff/planning/me?week=" + NEXT_MONDAY));
        assertThat(mine.get("rows")).hasSize(1);
        assertThat(mine.at("/rows/0/userId").asText()).isEqualTo(celine);
        assertThat(body(getAs(educ, "/api/v1/staff/timesheets/me/" + LAST_MONTH)).get("people")).hasSize(1);

        assertThat(status(getAs(compta, "/api/v1/staff"))).isEqualTo(200);
        assertThat(status(getAs(compta, "/api/v1/staff/timesheets/" + LAST_MONTH))).isEqualTo(200);
        assertThat(status(perform(put("/api/v1/staff/" + celine + "/profile"), compta,
                Map.of("contractType", "CDI")))).isEqualTo(403);
        assertThat(status(perform(post("/api/v1/staff/timesheets/" + LAST_MONTH + "/validate"), compta, null))).isEqualTo(403);
    }

    // ---------------------------------------------------------------- outils

    void signedContract(String childId, LocalDate start) throws Exception {
        var planning = Map.of("mode", "FIXE", "days", List.of(
                Map.of("dayOfWeek", 1, "arrivesAt", "07:30", "leavesAt", "16:30"),
                Map.of("dayOfWeek", 2, "arrivesAt", "07:30", "leavesAt", "16:30")));
        var draft = new LinkedHashMap<String, Object>();
        draft.put("startDate", start.toString());
        draft.put("endDate", start.plusYears(1).toString());
        draft.put("noticeMonths", 1);
        draft.put("planning", planning);
        draft.put("adaptationBilling", "AU_TEMPS");
        draft.put("adaptation", List.of());
        var created = perform(post("/api/v1/contracts"), director, Map.of("childId", childId, "contract", draft));
        assertThat(status(created)).as(created.getResponse().getContentAsString()).isEqualTo(201);
        var sign = multipart("/api/v1/contracts/" + body(created).get("id").asText() + "/versions/0/sign");
        sign.param("signedOn", TODAY.toString());
        assertThat(status(perform(sign, director, null))).isEqualTo(204);
    }

    static JsonNode slot(JsonNode forecast, String startsAt) {
        for (var s : forecast.get("slots")) {
            if (s.get("startsAt").asText().startsWith(startsAt)) {
                return s;
            }
        }
        throw new AssertionError(startsAt);
    }

    static JsonNode rowOf(JsonNode planning, String userId) {
        for (var r : planning.get("rows")) {
            if (r.get("userId").asText().equals(userId)) {
                return r;
            }
        }
        throw new AssertionError(userId);
    }

    static JsonNode personOf(JsonNode sheet, String userId) {
        for (var p : sheet.get("people")) {
            if (p.get("userId").asText().equals(userId)) {
                return p;
            }
        }
        throw new AssertionError(userId);
    }
}
