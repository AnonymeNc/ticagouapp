package nc.ticagou.meal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import nc.ticagou.IntegrationTest;

/** Repas comptés d'après les présences, corrections, menus et variantes, clôture trimestrielle, exports. */
class MealIT extends IntegrationTest {

    private static final ZoneId NOUMEA = ZoneId.of("Pacific/Noumea");
    /** Veille : journée passée, seuls les pointages comptent (pas de prévision). */
    private static final LocalDate DAY = LocalDate.now(NOUMEA).minusDays(1);

    private String director;
    private String moyens;

    @BeforeEach
    void setUp() throws Exception {
        director = install();
        moyens = sectionId(director, "Moyens");
    }

    private String at(LocalDate day, String time) {
        return day.atTime(java.time.LocalTime.parse(time)).atZone(NOUMEA).toInstant().toString();
    }

    private String child(String firstName) throws Exception {
        return createChild(director, firstName, "Famille" + firstName, "2025-01-15", moyens);
    }

    private void passage(String childId, LocalDate day, String from, String to) throws Exception {
        var request = new LinkedHashMap<String, Object>();
        request.put("childId", childId);
        request.put("arrivedAt", at(day, from));
        request.put("departedAt", to == null ? null : at(day, to));
        request.put("pickedUpBy", to == null ? null : "Sarah");
        request.put("reason", "Pointage de test");
        var result = perform(post("/api/v1/attendance/children/records"), director, request);
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(201);
    }

    private JsonNode kitchen(String token, LocalDate day) throws Exception {
        var result = getAs(token, "/api/v1/meals/kitchen?date=" + day);
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result);
    }

    private void health(String childId, String diet, String allergy, boolean packedLunch) throws Exception {
        var request = new LinkedHashMap<String, Object>();
        request.put("diet", diet);
        request.put("paiActive", false);
        request.put("allergies", allergy == null ? List.of() : List.of(Map.of("allergen", allergy, "severity", "MODEREE")));
        request.put("vaccinations", List.of());
        request.put("packedLunch", packedLunch);
        var result = perform(put("/api/v1/children/" + childId + "/health"), director, request);
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
    }

    private static Map<String, Object> item(int day, String course, String dietId, String label, List<String> tags) {
        var item = new LinkedHashMap<String, Object>();
        item.put("dayOfWeek", day);
        item.put("course", course);
        item.put("dietId", dietId);
        item.put("label", label);
        item.put("tags", tags);
        return item;
    }

    private String dietId(String label) throws Exception {
        for (var diet : body(getAs(director, "/api/v1/diets"))) {
            if (diet.get("label").asText().equals(label)) {
                return diet.get("id").asText();
            }
        }
        throw new IllegalArgumentException(label);
    }

    @Test
    void repasEtGoutersComptesDApresLesPresences() throws Exception {
        passage(child("Noa"), DAY, "08:00", "16:30");
        passage(child("Léo"), DAY, "08:00", "13:00");
        passage(child("Kaïna"), DAY, "14:00", "17:00");
        child("Absent");

        var view = kitchen(director, DAY);
        assertThat(view.at("/total/lunches").asInt()).isEqualTo(2);
        assertThat(view.at("/total/snacks").asInt()).isEqualTo(2);
        assertThat(view.at("/total/leavingBeforeSnack").asInt()).isEqualTo(1);
        var section = view.get("sections").get(1);
        assertThat(section.get("name").asText()).isEqualTo("Moyens");
        assertThat(section.get("children").asInt()).isEqualTo(3);
        assertThat(view.get("children")).hasSize(3);
        assertThat(view.at("/children/0/lunchBasis").asText()).isEqualTo("POINTAGE");
    }

    @Test
    void correctionManuelleAvecMotif() throws Exception {
        var leo = child("Léo");
        passage(leo, DAY, "08:00", "13:00");
        var noReason = perform(put("/api/v1/meals/adjustments"), director,
                Map.of("childId", leo, "day", DAY.toString(), "meal", "GOUTER", "served", true));
        assertThat(status(noReason)).isEqualTo(400);

        var adjusted = body(perform(put("/api/v1/meals/adjustments"), director, Map.of("childId", leo,
                "day", DAY.toString(), "meal", "GOUTER", "served", true, "reason", "Goûter pris avant le départ")));
        assertThat(adjusted.at("/total/snacks").asInt()).isEqualTo(1);
        assertThat(adjusted.at("/children/0/snackBasis").asText()).isEqualTo("CORRECTION");
        assertThat(adjusted.at("/children/0/snackAdjustment/reason").asText()).isEqualTo("Goûter pris avant le départ");
        assertThat(auditCount("MEAL_ADJUSTED")).isEqualTo(1);

        var reset = new LinkedHashMap<String, Object>();
        reset.put("childId", leo);
        reset.put("day", DAY.toString());
        reset.put("meal", "GOUTER");
        reset.put("served", null);
        assertThat(body(perform(put("/api/v1/meals/adjustments"), director, reset)).at("/total/snacks").asInt()).isZero();
    }

    @Test
    void enfantsAAdapterDApresLeMenuEtLeRegime() throws Exception {
        var noa = child("Noa");
        var hina = child("Hina");
        var kalani = child("Kalani");
        passage(noa, DAY, "08:00", "16:30");
        passage(hina, DAY, "08:00", "16:30");
        health(noa, "végétarien", "Lait", false);
        health(hina, null, "Lait", true);
        health(kalani, "Sans gluten", null, false);

        var vegetarian = dietId("Végétarien");
        var monday = DAY.with(DayOfWeek.MONDAY);
        int dow = DAY.getDayOfWeek().getValue();
        var saved = perform(put("/api/v1/menus/" + monday), director, Map.of("status", "PUBLIE", "items", List.of(
                item(dow, "ENTREE", null, "Carottes râpées au citron", List.of()),
                item(dow, "PLAT", null, "Poulet au curry doux, riz", List.of("viande")),
                item(dow, "VARIANTE", vegetarian, "Lentilles corail au curry", List.of()),
                item(dow, "LAITAGE", null, "Yaourt nature", List.of("lait")))));
        assertThat(status(saved)).as(saved.getResponse().getContentAsString()).isEqualTo(200);

        var view = kitchen(director, DAY);
        assertThat(view.get("menu")).hasSize(4);
        var toAdapt = view.get("toAdapt");
        assertThat(toAdapt).hasSize(2);
        var hinaRow = toAdapt.get(0);
        assertThat(hinaRow.get("firstName").asText()).isEqualTo("Hina");
        assertThat(hinaRow.get("packedLunch").asBoolean()).isTrue();
        assertThat(hinaRow.at("/advice/0").asText()).startsWith("Panier repas fourni par la famille");
        var noaRow = toAdapt.get(1);
        assertThat(noaRow.get("diet").asText()).isEqualTo("Végétarien");
        assertThat(noaRow.get("advice").findValuesAsText("")).isEmpty();
        assertThat(noaRow.get("advice").toString()).contains("Laitage : contient lait, à remplacer.")
                .contains("Poulet au curry doux → Lentilles corail au curry.");
        // Panier repas : ni repas ni goûter à préparer.
        assertThat(view.at("/total/lunches").asInt()).isEqualTo(1);
        assertThat(view.at("/total/packedLunches").asInt()).isEqualTo(1);
    }

    @Test
    void varianteManquanteEtCopieDeLaSemaine() throws Exception {
        var maelys = child("Maëlys");
        health(maelys, "Sans porc", null, false);
        var monday = LocalDate.now(NOUMEA).with(DayOfWeek.MONDAY);
        perform(put("/api/v1/menus/" + monday), director, Map.of("status", "BROUILLON", "items", List.of(
                item(1, "PLAT", null, "Poulet au curry", List.of("viande")),
                item(3, "PLAT", null, "Rôti de porc, purée", List.of("porc", "lait")))));

        var week = body(getAs(director, "/api/v1/menus?date=" + monday));
        assertThat(week.get("warnings")).hasSize(1);
        assertThat(week.at("/warnings/0").asText()).startsWith("Mercredi").contains("Sans porc").contains("Maëlys FamilleMaëlys");
        var row = week.get("diets").get(0);
        assertThat(row.get("label").asText()).isEqualTo("Sans porc");
        assertThat(row.at("/days/0/status").asText()).isEqualTo("CONVIENT");
        assertThat(row.at("/days/2/status").asText()).isEqualTo("MANQUANTE");

        var copied = body(perform(post("/api/v1/menus/" + monday.plusWeeks(1) + "/copy-previous"), director, null));
        assertThat(copied.get("items")).hasSize(2);
        assertThat(copied.get("status").asText()).isEqualTo("BROUILLON");

        var tuesday = perform(put("/api/v1/menus/" + monday.plusDays(1)), director,
                Map.of("status", "BROUILLON", "items", List.of()));
        assertThat(body(tuesday).get("code").asText()).isEqualTo("not_monday");
        var badTag = perform(put("/api/v1/menus/" + monday), director, Map.of("status", "BROUILLON",
                "items", List.of(item(1, "PLAT", null, "Salade de kiwi", List.of("kiwi")))));
        assertThat(body(badTag).get("code").asText()).isEqualTo("unknown_tag");
    }

    @Test
    void clotureDuTrimestreFigeLesComptes() throws Exception {
        var today = LocalDate.now(NOUMEA);
        int current = (today.getMonthValue() - 1) / 3 + 1;
        int year = current == 1 ? today.getYear() - 1 : today.getYear();
        int quarter = current == 1 ? 4 : current - 1;
        var day = LocalDate.of(year, (quarter - 1) * 3 + 1, 1).plusDays(14);
        var noa = child("Noa");
        // Passage resté ouvert : la clôture attend qu'il soit vérifié.
        passage(noa, day, "08:00", null);
        var refused = perform(post("/api/v1/meals/quarters/" + year + "/" + quarter + "/close"), director, null);
        assertThat(body(refused).get("code").asText()).isEqualTo("attendance_to_review");

        var record = body(getAs(director, "/api/v1/attendance?date=" + day)).at("/children/0/id").asText();
        var correction = new LinkedHashMap<String, Object>();
        correction.put("arrivedAt", at(day, "08:00"));
        correction.put("departedAt", at(day, "16:30"));
        correction.put("sectionId", moyens);
        correction.put("pickedUpBy", "Sarah");
        correction.put("reason", "Départ oublié");
        assertThat(status(perform(put("/api/v1/attendance/children/records/" + record), director, correction))).isEqualTo(204);

        var summary = body(getAs(director, "/api/v1/meals/quarters/" + year + "/" + quarter));
        assertThat(summary.get("state").asText()).isEqualTo("A_CLORE");
        assertThat(summary.at("/total/lunches").asInt()).isEqualTo(1);
        assertThat(summary.at("/total/openDays").asInt()).isEqualTo(1);
        assertThat(summary.at("/total/lunchesBySection/" + moyens).asInt()).isEqualTo(1);

        var closed = body(perform(post("/api/v1/meals/quarters/" + year + "/" + quarter + "/close"), director, null));
        assertThat(closed.get("state").asText()).isEqualTo("CLOS");
        assertThat(auditCount("MEAL_QUARTER_CLOSED")).isEqualTo(1);

        correction.put("reason", "Après clôture");
        correction.put("departedAt", at(day, "17:00"));
        var locked = perform(put("/api/v1/attendance/children/records/" + record), director, correction);
        assertThat(body(locked).get("code").asText()).isEqualTo("quarter_closed");
        var lockedMeal = perform(put("/api/v1/meals/adjustments"), director, Map.of("childId", noa,
                "day", day.toString(), "meal", "REPAS", "served", false, "reason", "Après clôture"));
        assertThat(body(lockedMeal).get("code").asText()).isEqualTo("quarter_closed");

        var noReason = perform(post("/api/v1/meals/quarters/" + year + "/" + quarter + "/reopen"), director, Map.of());
        assertThat(status(noReason)).isEqualTo(400);
        var reopened = body(perform(post("/api/v1/meals/quarters/" + year + "/" + quarter + "/reopen"), director,
                Map.of("reason", "Erreur de pointage signalée par la famille")));
        assertThat(reopened.get("state").asText()).isEqualTo("A_CLORE");
        assertThat(status(perform(put("/api/v1/attendance/children/records/" + record), director, correction))).isEqualTo(204);

        var currentQuarter = perform(post("/api/v1/meals/quarters/" + today.getYear() + "/" + current + "/close"), director, null);
        assertThat(body(currentQuarter).get("code").asText()).isEqualTo("quarter_not_over");
    }

    @Test
    void exportsPdfEtExcel() throws Exception {
        passage(child("Noa"), DAY, "08:00", "16:30");
        int quarter = (DAY.getMonthValue() - 1) / 3 + 1;
        var pdf = getAs(director, "/api/v1/meals/quarters/" + DAY.getYear() + "/" + quarter + "/pdf");
        assertThat(status(pdf)).isEqualTo(200);
        assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
        var xlsx = getAs(director, "/api/v1/meals/quarters/" + DAY.getYear() + "/" + quarter + "/xlsx");
        assertThat(status(xlsx)).isEqualTo(200);
        var bytes = xlsx.getResponse().getContentAsByteArray();
        assertThat(new String(bytes, 0, 2, StandardCharsets.ISO_8859_1)).isEqualTo("PK");
        // Lu par son répertoire central, comme Excel (les entrées sont écrites en flux).
        var file = java.nio.file.Files.createTempFile("repas", ".xlsx");
        try {
            java.nio.file.Files.write(file, bytes);
            try (var zip = new java.util.zip.ZipFile(file.toFile())) {
                var names = zip.stream().map(java.util.zip.ZipEntry::getName).toList();
                assertThat(names).contains("xl/workbook.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml");
                var sheet = new String(zip.getInputStream(zip.getEntry("xl/workbook.xml")).readAllBytes(), StandardCharsets.UTF_8);
                assertThat(sheet).contains("R&#xe9;capitulatif").contains("D&#xe9;tail par enfant et par jour");
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
        assertThat(xlsx.getResponse().getHeader("Content-Disposition")).contains("repas-" + DAY.getYear() + "-T" + quarter + ".xlsx");
        assertThat(auditCount("MEAL_EXPORTED")).isEqualTo(2);
    }

    @Test
    void droitsParRole() throws Exception {
        createUser(director, "CUISINE", "paulo.t", "Cuisine-2026!", null);
        createUser(director, "EDUCATRICE", "malia.t", "Moyens-2026!", null);
        createUser(director, "COMPTABILITE", "annie.d", "Comptes-2026!", null);
        var cook = accessToken("paulo.t", "Cuisine-2026!");
        var educator = accessToken("malia.t", "Moyens-2026!");
        var accountant = accessToken("annie.d", "Comptes-2026!");
        var leo = child("Léo");
        passage(leo, DAY, "08:00", "13:00");
        var adjustment = Map.of("childId", leo, "day", DAY.toString(), "meal", "GOUTER", "served", true, "reason", "Test");

        assertThat(status(getAs(accountant, "/api/v1/meals/kitchen"))).isEqualTo(403);
        assertThat(status(getAs(educator, "/api/v1/meals/kitchen"))).isEqualTo(200);
        assertThat(status(perform(put("/api/v1/meals/adjustments"), educator, adjustment))).isEqualTo(403);
        assertThat(status(perform(put("/api/v1/meals/adjustments"), cook, adjustment))).isEqualTo(200);
        assertThat(status(perform(post("/api/v1/meals/quarters/2026/1/close"), cook, null))).isEqualTo(403);
        assertThat(status(perform(post("/api/v1/diets"), cook,
                Map.of("label", "Sans œuf", "excludes", List.of("œuf"), "displayOrder", 4)))).isEqualTo(201);
        assertThat(status(perform(put("/api/v1/meals/settings"), cook,
                Map.of("lunchTime", "11:45", "snackTime", "15:30")))).isEqualTo(403);
        var settings = perform(put("/api/v1/meals/settings"), director, Map.of("lunchTime", "11:45", "snackTime", "11:00"));
        assertThat(status(settings)).isEqualTo(400);
    }
}
