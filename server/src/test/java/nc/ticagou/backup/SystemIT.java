package nc.ticagou.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;

import nc.ticagou.IntegrationTest;
import nc.ticagou.license.TestLicenses;

/** Paramètres de la phase 8 : structure et banque, sections, tableau de bord, licence, sauvegardes. */
class SystemIT extends IntegrationTest {

    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Pacific/Noumea"));

    @Autowired
    PgTools pg;

    @Autowired
    BackupService backups;

    String director;

    @BeforeEach
    void installer() throws Exception {
        director = install();
    }

    Map<String, Object> structureUpdate(Map<String, Object> bank) throws Exception {
        var current = body(getAs(director, "/api/v1/structure"));
        var identity = new LinkedHashMap<String, Object>();
        identity.put("name", current.get("name").asText());
        identity.put("city", "Nouméa");
        identity.put("ridet", current.get("ridet").asText());
        identity.put("agrementNumber", current.get("agrementNumber").asText());
        identity.put("agrementDate", current.get("agrementDate").asText());
        identity.put("capacity", current.get("capacity").asInt());
        var request = new LinkedHashMap<String, Object>();
        request.put("identity", identity);
        request.put("invoiceFooter", "Association loi 1901");
        request.put("openingHours", json.convertValue(current.get("openingHours"), List.class));
        request.put("contact", Map.of("address", "12 rue Jules Ferry", "postalCode", "98800", "phone", "26 12 34",
                "email", "contact@niaoulis.nc"));
        request.put("bank", bank);
        return request;
    }

    @Test
    void coordonneesEtBanqueAvecIbanControle() throws Exception {
        var bad = perform(put("/api/v1/structure"), director, structureUpdate(Map.of("holder", "Association Les P’tits Niaoulis",
                "bankName", "BCI", "iban", "FR76 3000 6000 0112 3456 7890 188", "bic", "BCINNCNX", "printRib", true)));
        assertThat(status(bad)).isEqualTo(400);
        assertThat(bad.getResponse().getContentAsString()).contains("IBAN");

        var ok = perform(put("/api/v1/structure"), director, structureUpdate(Map.of("holder", "Association Les P’tits Niaoulis",
                "bankName", "BCI", "iban", "fr76 3000 6000 0112 3456 7890 189", "bic", "bcinncnx", "printRib", true)));
        assertThat(status(ok)).as(ok.getResponse().getContentAsString()).isEqualTo(200);
        var structure = body(ok);
        assertThat(structure.at("/bank/iban").asText()).isEqualTo("FR7630006000011234567890189");
        assertThat(structure.at("/bank/bic").asText()).isEqualTo("BCINNCNX");
        assertThat(structure.at("/contact/postalCode").asText()).isEqualTo("98800");

        // Sans les blocs facultatifs, coordonnées et banque restent inchangées.
        var without = structureUpdate(null);
        without.remove("contact");
        without.remove("bank");
        assertThat(body(perform(put("/api/v1/structure"), director, without)).at("/bank/printRib").asBoolean()).isTrue();
    }

    @Test
    void sectionsTrousEtPassages() throws Exception {
        var overview = body(getAs(director, "/api/v1/sections/overview"));
        assertThat(overview.get("sections")).hasSize(3);
        assertThat(overview.get("gaps")).isEmpty();
        assertThat(overview.get("totalPlaces").asInt()).isEqualTo(48);

        var moyens = sectionId(director, "Moyens");
        var bebes = sectionId(director, "Bébés");
        // Un bébé de 17 mois et demi : il atteint 18 mois dans moins d'un mois.
        var born = TODAY.minusMonths(18).plusDays(10);
        var noa = createChild(director, "Noa", "Wahéo", born.toString(), bebes, born.plusMonths(3).toString());
        var passages = body(getAs(director, "/api/v1/sections/overview")).get("passages");
        assertThat(passages).hasSize(1);
        assertThat(passages.at("/0/toSectionId").asText()).isEqualTo(moyens);
        assertThat(passages.at("/0/on").asText()).endsWith("-01");

        var moved = body(perform(post("/api/v1/sections/passages"), director, Map.of("childId", noa, "toSectionId", moyens)));
        assertThat(moved.get("passages")).isEmpty();
        assertThat(auditCount("CHILD_SECTION_CHANGED")).isEqualTo(1);

        var rules = body(perform(put("/api/v1/sections/passage-rules"), director,
                Map.of("propose", false, "firstOfMonth", true, "blockFull", true)));
        assertThat(rules.at("/rules/propose").asBoolean()).isFalse();
    }

    @Test
    void tableauDeBordParRole() throws Exception {
        createUser(director, "COMPTABILITE", "compta", "Comptable-2026!", "4821");
        createUser(director, "EDUCATRICE", "educ", "Educatrice-2026!", "4822");
        var compta = accessToken("compta", "Comptable-2026!");
        var educ = accessToken("educ", "Educatrice-2026!");

        var today = body(getAs(director, "/api/v1/dashboard/today"));
        assertThat(today.get("day").asText()).isEqualTo(TODAY.toString());
        assertThat(today.get("absences")).isEmpty();
        var kpis = body(getAs(director, "/api/v1/dashboard/kpis"));
        assertThat(kpis.get("capacity").asInt()).isEqualTo(48);
        assertThat(kpis.get("fillRate").asInt()).isZero();

        assertThat(status(getAs(educ, "/api/v1/dashboard/today"))).isEqualTo(200);
        assertThat(status(getAs(educ, "/api/v1/dashboard/kpis"))).isEqualTo(403);
        assertThat(status(getAs(compta, "/api/v1/dashboard/kpis"))).isEqualTo(200);
        assertThat(status(getAs(compta, "/api/v1/dashboard/today"))).isEqualTo(403);
    }

    @Test
    void abonnementEtTablettesLimitees() throws Exception {
        var evaluation = body(getAs(director, "/api/v1/system/license"));
        assertThat(evaluation.get("state").asText()).isEqualTo("EVALUATION");

        TestLicenses.apply(jdbc, 1, TODAY.plusDays(20));
        var active = body(getAs(director, "/api/v1/system/license"));
        assertThat(active.get("state").asText()).as("échéance dans 20 jours").isEqualTo("A_RENOUVELER");
        assertThat(active.at("/license/tablets").asInt()).isEqualTo(1);

        pairDevice(director, "Accueil");
        var refused = perform(post("/api/v1/devices"), director, Map.of("name", "Salle des Bébés"));
        assertThat(status(refused)).isEqualTo(422);
        assertThat(body(refused).get("code").asText()).isEqualTo("license_tablets");
    }

    @Test
    void formuleEssentielleLimiteeALaBase() throws Exception {
        TestLicenses.apply(jdbc, 60, java.util.Set.of(), 2, TODAY.plusYears(1));

        assertThat(body(getAs(director, "/api/v1/system/modules")).get("modules")).isEmpty();
        var contract = perform(post("/api/v1/contracts"), director, Map.of());
        assertThat(status(contract)).isEqualTo(403);
        assertThat(body(contract).get("code").asText()).isEqualTo("module_absent");
        assertThat(status(perform(post("/api/v1/enrollment-requests"), director, Map.of()))).isEqualTo(403);
        assertThat(status(perform(post("/api/v1/children"), director, Map.of()))).as("la base reste ouverte (400 = validation)").isEqualTo(400);
    }

    @Test
    void uneFormuleEntraineLesModulesDontElleABesoin() throws Exception {
        TestLicenses.apply(jdbc, 60, java.util.EnumSet.of(nc.ticagou.license.LicenseModule.COMPTABILITE), 2, TODAY.plusYears(1));

        assertThat(body(getAs(director, "/api/v1/system/modules")).get("modules")).extracting(JsonNode::asText)
                .containsExactly("INSCRIPTIONS", "FACTURATION", "COMPTABILITE");
    }

    @Test
    void abonnementLieAuxPlacesEtAuxModules() throws Exception {
        // Évaluation : tout est ouvert.
        assertThat(status(perform(put("/api/v1/staff/settings"), director, Map.of("alertDays", 30, "defaultBreakMinutes", 60, "referenceWeeklyMinutes", 2340)))).isEqualTo(200);

        TestLicenses.apply(jdbc, 50, java.util.EnumSet.of(nc.ticagou.license.LicenseModule.REPAS), 2, TODAY.plusYears(1));

        var refused = perform(put("/api/v1/staff/settings"), director, Map.of("alertDays", 30, "defaultBreakMinutes", 60, "referenceWeeklyMinutes", 2340));
        assertThat(status(refused)).isEqualTo(403);
        assertThat(body(refused).get("code").asText()).isEqualTo("module_absent");
        assertThat(body(refused).get("detail").asText()).contains("Personnel");
        assertThat(status(getAs(director, "/api/v1/staff/alerts"))).as("lecture permise").isEqualTo(200);
        assertThat(body(getAs(director, "/api/v1/system/modules")).get("modules")).extracting(JsonNode::asText)
                .containsExactly("REPAS");
        assertThat(status(getAs(director, "/api/v1/billing/collections"))).isEqualTo(200);

        var structure = structureUpdate(Map.of("printRib", false));
        @SuppressWarnings("unchecked")
        var identity = (Map<String, Object>) structure.get("identity");
        identity.put("capacity", 55);
        var capped = perform(put("/api/v1/structure"), director, structure);
        assertThat(body(capped).get("code").asText()).isEqualTo("license_places");
    }

    @Test
    void sauvegardeChiffreeEtTestDeRestauration() throws Exception {
        assumeTrue(pg.available(), "pg_dump et pg_restore absents de ce poste");
        createChild(director, "Kaïna", "Wamytan", "2024-03-02", sectionId(director, "Grands"));

        var run = body(perform(post("/api/v1/backups/run"), director, null));
        assertThat(run.get("status").asText()).isEqualTo("EN_COURS");
        var done = waitFor(run.get("id").asText());
        assertThat(done.get("status").asText()).as(done.toString()).isEqualTo("REUSSIE");
        assertThat(done.get("copies").asInt()).isEqualTo(1);
        try (var files = Files.list(BACKUPS)) {
            var archive = files.findFirst().orElseThrow();
            assertThat(archive.getFileName().toString()).matches("ticagou-\\d{4}-\\d{2}-\\d{2}-\\d{4}\\.tcb");
            assertThat(new String(Files.readAllBytes(archive), 0, 11, StandardCharsets.US_ASCII)).isEqualTo("TICAGOU-BK1");
        }

        backups.runNightlyIfDue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backup_run WHERE kind = 'AUTOMATIQUE'", Integer.class))
                .as("la sauvegarde manuelle du jour tient lieu de celle de la nuit").isZero();

        var test = body(perform(post("/api/v1/backups/restore-test"), director, null));
        var tested = waitFor(test.get("id").asText());
        assertThat(tested.get("status").asText()).as(tested.toString()).isEqualTo("REUSSIE");
        assertThat(tested.get("detail").asText()).contains("tables");
        var overview = body(getAs(director, "/api/v1/backups"));
        assertThat(overview.get("lastTestOk").asBoolean()).isTrue();
        assertThat(overview.at("/lastSuccess/id").asText()).isEqualTo(run.get("id").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_database WHERE datname LIKE 'ticagou_test_restauration_%'",
                Integer.class)).as("base temporaire supprimée").isZero();
    }

    JsonNode waitFor(String runId) throws Exception {
        for (int i = 0; i < 240; i++) {
            for (var r : body(getAs(director, "/api/v1/backups")).get("history")) {
                if (r.get("id").asText().equals(runId) && !r.get("status").asText().equals("EN_COURS")) {
                    return r;
                }
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Sauvegarde non terminée");
    }
}
