package nc.ticagou.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

import nc.ticagou.IntegrationTest;

/**
 * Jeu de données des tests de facturation : le mois facturé est le mois précédent ; deux enfants sous contrat
 * signé depuis deux mois (lundi → vendredi, 07:30 – 16:30), chacun dans sa famille.
 */
abstract class BillingFixtures extends IntegrationTest {

    static final ZoneId NOUMEA = ZoneId.of("Pacific/Noumea");
    static final LocalDate TODAY = LocalDate.now(NOUMEA);
    static final YearMonth MONTH = YearMonth.from(TODAY).minusMonths(1);
    static final int YEAR = TODAY.getYear();
    /** Deuxième mardi du mois facturé : jour prévu au planning. */
    static final LocalDate TUESDAY = MONTH.atDay(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.TUESDAY)).plusWeeks(1);

    String director;
    String moyens;
    String lea;
    String tom;

    @BeforeEach
    void billingData() throws Exception {
        director = install();
        moyens = sectionId(director, "Moyens");
        createVolumeTariff(director, "2020-01-01");
        lea = createChild(director, "Léa", "Wamytan", "2025-01-15", moyens);
        tom = createChild(director, "Tom", "Poédi", "2024-11-02", moyens);
        signedContract(lea, MONTH.minusMonths(2).atDay(1));
        signedContract(tom, MONTH.minusMonths(2).atDay(1));
    }

    static Map<String, Object> day(int day, String arrives, String leaves) {
        return Map.of("dayOfWeek", day, "arrivesAt", arrives, "leavesAt", leaves);
    }

    String signedContract(String childId, LocalDate start) throws Exception {
        return signedContract(childId, start, List.of(day(1, "07:30", "16:30"), day(2, "07:30", "16:30"),
                day(3, "07:30", "16:30"), day(4, "07:30", "16:30"), day(5, "07:30", "16:30")), null);
    }

    /** {@code billingMode} nul : mode par défaut des règles de facturation. */
    String signedContract(String childId, LocalDate start, List<Map<String, Object>> days, String billingMode)
            throws Exception {
        var planning = new LinkedHashMap<String, Object>();
        planning.put("mode", "FIXE");
        planning.put("days", days);
        planning.put("billingMode", billingMode);
        var draft = new LinkedHashMap<String, Object>();
        draft.put("startDate", start.toString());
        draft.put("endDate", start.plusYears(1).minusDays(1).toString());
        draft.put("noticeMonths", 1);
        draft.put("planning", planning);
        draft.put("adaptationBilling", "AU_TEMPS");
        draft.put("adaptation", List.of());
        var created = perform(post("/api/v1/contracts"), director, Map.of("childId", childId, "contract", draft));
        assertThat(status(created)).as(created.getResponse().getContentAsString()).isEqualTo(201);
        var id = body(created).get("id").asText();
        var sign = multipart("/api/v1/contracts/" + id + "/versions/0/sign");
        sign.param("signedOn", TODAY.toString());
        assertThat(status(perform(sign, director, null))).isEqualTo(204);
        return id;
    }

    long monthlyFee(String childId) throws Exception {
        var contracts = body(getAs(director, "/api/v1/contracts?childId=" + childId));
        return contracts.get(0).at("/versions/0/monthlyFee").asLong();
    }

    static String at(LocalDate day, String time) {
        return day.atTime(LocalTime.parse(time)).atZone(NOUMEA).toInstant().toString();
    }

    void passage(String childId, LocalDate day, String from, String to) throws Exception {
        var request = new LinkedHashMap<String, Object>();
        request.put("childId", childId);
        request.put("arrivedAt", at(day, from));
        request.put("departedAt", to == null ? null : at(day, to));
        request.put("pickedUpBy", to == null ? null : "Sarah");
        request.put("reason", "Pointage de test");
        var result = perform(post("/api/v1/attendance/children/records"), director, request);
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(201);
    }

    void completeDeparture(String childId, LocalDate day, String arrived, String departed) throws Exception {
        for (var record : body(getAs(director, "/api/v1/attendance?date=" + day)).get("children")) {
            if (record.get("childId").asText().equals(childId)) {
                var correction = new LinkedHashMap<String, Object>();
                correction.put("arrivedAt", at(day, arrived));
                correction.put("departedAt", at(day, departed));
                correction.put("sectionId", moyens);
                correction.put("pickedUpBy", "Sarah");
                correction.put("reason", "Départ oublié");
                var result = perform(put("/api/v1/attendance/children/records/" + record.get("id").asText()),
                        director, correction);
                assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(204);
                return;
            }
        }
        throw new IllegalStateException("Aucun pointage le " + day);
    }

    String funder(String name, String calculation, long amount, int rate, Long cap, int priority) throws Exception {
        var request = new LinkedHashMap<String, Object>();
        request.put("name", name);
        request.put("kind", "AIDE_FAMILLE");
        request.put("category", "ORGANISME");
        request.put("calculation", calculation);
        request.put("amount", amount);
        request.put("rate", rate);
        request.put("rateBase", "FRAIS_GARDE");
        request.put("monthlyCap", cap);
        request.put("priority", priority);
        request.put("validFrom", "2024-01-01");
        request.put("conventionRef", "CONV-" + priority);
        var result = perform(post("/api/v1/billing/funders"), director, request);
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result).get("id").asText();
    }

    void grant(String childId, String funderId, String beneficiary) throws Exception {
        var request = new LinkedHashMap<String, Object>();
        request.put("funderId", funderId);
        request.put("fromDay", MONTH.minusMonths(2).atDay(1).toString());
        request.put("beneficiary", beneficiary);
        request.put("fileNumber", "A-123");
        var result = perform(post("/api/v1/children/" + childId + "/fundings"), director, request);
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(201);
    }

    JsonNode preview() throws Exception {
        var result = getAs(director, "/api/v1/billing/months/" + MONTH + "/preview");
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result);
    }

    JsonNode row(JsonNode preview, String familyLabel) {
        for (var family : preview.get("families")) {
            if (family.get("familyLabel").asText().equals(familyLabel)) {
                return family;
            }
        }
        throw new AssertionError("Famille absente : " + familyLabel);
    }

    MvcResult generate() throws Exception {
        return perform(post("/api/v1/billing/months/" + MONTH + "/generate"), director, Map.of());
    }

    JsonNode generated() throws Exception {
        var result = generate();
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result);
    }

    JsonNode invoices() throws Exception {
        return body(getAs(director, "/api/v1/billing/invoices?month=" + MONTH));
    }

    String invoiceOf(String familyLabel) throws Exception {
        for (var invoice : invoices()) {
            if (invoice.get("familyLabel").asText().equals(familyLabel) && !invoice.get("status").asText().equals("ANNULEE")) {
                return invoice.get("id").asText();
            }
        }
        throw new AssertionError("Pas de facture pour " + familyLabel);
    }

    static String number(String series, int n) {
        return "%s-%d-%04d".formatted(series, YEAR, n);
    }
}
