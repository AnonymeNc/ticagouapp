package nc.ticagou.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class BillingIT extends BillingFixtures {

    String provinceSud;
    String cafat;

    void aidsForLea() throws Exception {
        provinceSud = funder("Province Sud", "POURCENTAGE", 0, 200, 30_000L, 0);
        cafat = funder("CAFAT", "MONTANT_FIXE", 12_000, 0, null, 1);
        grant(lea, provinceSud, "Sarah Wamytan");
        grant(lea, cafat, "Sarah Wamytan");
    }

    @Test
    void previsualisationPuisGenerationSansTrou() throws Exception {
        aidsForLea();
        passage(lea, TUESDAY, "07:30", "17:45");
        passage(tom, TUESDAY, "07:30", null);

        var preview = preview();
        assertThat(preview.get("generationAllowed").asBoolean()).isTrue();
        assertThat(preview.at("/totals/families").asInt()).isEqualTo(2);
        assertThat(preview.at("/totals/ready").asInt()).isEqualTo(1);
        assertThat(preview.at("/totals/withAnomalies").asInt()).isEqualTo(1);
        assertThat(preview.get("anomalies")).hasSize(1);
        assertThat(preview.at("/anomalies/0/type").asText()).isEqualTo("DEPART_MANQUANT");
        assertThat(preview.at("/anomalies/0/childName").asText()).isEqualTo("Tom Poédi");
        assertThat(preview.get("funders")).extracting(f -> f.get("name").asText())
                .containsExactly("Province Sud", "CAFAT");

        var wamytan = row(preview, "Famille Wamytan");
        long forfait = monthlyFee(lea);
        assertThat(wamytan.get("status").asText()).isEqualTo("PRETE");
        assertThat(wamytan.get("forfait").asLong()).isEqualTo(forfait);
        assertThat(wamytan.get("extraHours").asLong()).as("16:30 → 17:30 au tarif du contrat").isEqualTo(510);
        assertThat(wamytan.get("overtime").asLong()).as("départ à 17:45, fermeture 17:30").isEqualTo(1_000);
        long care = forfait + 510 + 1_000;
        long province = Math.min((care * 2 + 5) / 10, 30_000);
        assertThat(wamytan.get("careTotal").asLong()).isEqualTo(care);
        assertThat(wamytan.at("/aids/" + provinceSud).asLong()).isEqualTo(province);
        assertThat(wamytan.at("/aids/" + cafat).asLong()).isEqualTo(12_000);
        assertThat(wamytan.get("familyTotal").asLong()).isEqualTo(care - province - 12_000);
        assertThat(row(preview, "Famille Poédi").get("status").asText()).isEqualTo("ANOMALIE");

        var detail = body(getAs(director, "/api/v1/billing/months/" + MONTH + "/preview/" + wamytan.get("familyId").asText()));
        assertThat(detail.get("lines")).extracting(l -> l.get("kind").asText())
                .containsExactly("FORFAIT", "HEURES_COMPLEMENTAIRES", "DEPASSEMENT", "AIDE", "AIDE");
        assertThat(detail.get("contractText").asText()).isEqualTo("5 jours par semaine, 07:30 à 16:30");

        var first = generated();
        assertThat(first.get("invoices").asInt()).isEqualTo(1);
        assertThat(first.get("firstNumber").asText()).isEqualTo(number("FA", 1));
        assertThat(first.get("skipped")).extracting(s -> s.asText()).containsExactly("Famille Poédi — anomalie à corriger");
        assertThat(first.get("funderRequests")).extracting(r -> r.get("number").asText())
                .containsExactly(number("DF", 1), number("DF", 2));
        assertThat(auditCount("INVOICES_GENERATED")).isEqualTo(1);

        completeDeparture(tom, TUESDAY, "07:30", "16:30");
        var again = preview();
        assertThat(row(again, "Famille Wamytan").get("status").asText()).isEqualTo("FACTUREE");
        assertThat(row(again, "Famille Wamytan").get("invoiceNumber").asText()).isEqualTo(number("FA", 1));
        assertThat(row(again, "Famille Poédi").get("status").asText()).isEqualTo("PRETE");
        var second = generated();
        assertThat(second.get("firstNumber").asText()).as("numérotation continue").isEqualTo(number("FA", 2));
        assertThat(second.get("funderRequests")).isEmpty();

        var nothing = generate();
        assertThat(status(nothing)).isEqualTo(422);
        assertThat(body(nothing).get("code").asText()).isEqualTo("billing_nothing_ready");

        var invoice = body(getAs(director, "/api/v1/billing/invoices/" + invoiceOf("Famille Wamytan")));
        assertThat(invoice.at("/summary/status").asText()).isEqualTo("EMISE");
        assertThat(invoice.at("/summary/remaining").asLong()).isEqualTo(care - province - 12_000);
        assertThat(invoice.at("/summary/dueOn").asText()).isEqualTo(TODAY.plusDays(10).toString());
        assertThat(invoice.get("events")).extracting(e -> e.get("kind").asText()).containsExactly("GENEREE");
    }

    @Test
    void unEchecDansLeLotAnnuleToutSansConsommerDeNumero() throws Exception {
        var tomFamily = jdbc.queryForObject("SELECT family_id::text FROM child WHERE id = ?::uuid", String.class, tom);
        jdbc.execute("""
                CREATE FUNCTION test_refuse_invoice() RETURNS trigger AS $$
                BEGIN
                    IF NEW.family_id = '%s'::uuid THEN RAISE EXCEPTION 'échec simulé'; END IF;
                    RETURN NEW;
                END; $$ LANGUAGE plpgsql""".formatted(tomFamily));
        jdbc.execute("CREATE TRIGGER test_refuse_invoice BEFORE INSERT ON invoice FOR EACH ROW EXECUTE FUNCTION test_refuse_invoice()");
        try {
            assertThatThrownBy(this::generate).hasStackTraceContaining("simul");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM invoice", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM structure_counter WHERE name = 'invoice'", Long.class))
                    .as("le compteur est annulé avec le reste").isZero();
        } finally {
            jdbc.execute("DROP TRIGGER test_refuse_invoice ON invoice");
            jdbc.execute("DROP FUNCTION test_refuse_invoice()");
        }
        var result = generated();
        assertThat(result.get("invoices").asInt()).isEqualTo(2);
        assertThat(result.get("firstNumber").asText()).isEqualTo(number("FA", 1));
        assertThat(result.get("lastNumber").asText()).isEqualTo(number("FA", 2));
    }

    @Test
    void contratAuReelFactureLesJoursDuPlanningDuMois() throws Exception {
        var noa = createChild(director, "Noa", "Poiwi", "2024-06-01", moyens);
        signedContract(noa, MONTH.minusMonths(2).atDay(1), List.of(day(2, "08:00", "17:00")), "REEL");
        var version = body(getAs(director, "/api/v1/contracts?childId=" + noa)).get(0).at("/versions/0");
        assertThat(version.get("billingMode").asText()).isEqualTo("REEL");

        long tuesdays = MONTH.atDay(1).datesUntil(MONTH.atEndOfMonth().plusDays(1))
                .filter(d -> d.getDayOfWeek() == java.time.DayOfWeek.TUESDAY).count();
        var row = row(preview(), "Famille Poiwi");
        assertThat(row.get("forfait").asLong()).as("%d mardis de 9 h", tuesdays)
                .isEqualTo(tuesdays * 9 * version.get("hourlyRate").asLong());
        assertThat(row.get("forfait").asLong()).isNotEqualTo(version.get("monthlyFee").asLong());
    }

    @Test
    void modeParDefautDesReglesDeFacturation() throws Exception {
        var rule = body(getAs(director, "/api/v1/billing/rules")).get(0);
        @SuppressWarnings("unchecked")
        var update = (Map<String, Object>) json.convertValue(rule, Map.class);
        update.put("defaultBillingMode", "REEL");
        assertThat(status(perform(put("/api/v1/billing/rules/" + rule.get("id").asText()), director, update)))
                .isIn(200, 204);

        var noa = createChild(director, "Noa", "Poiwi", "2024-06-01", moyens);
        signedContract(noa, MONTH.minusMonths(2).atDay(1), List.of(day(2, "08:00", "17:00")), null);
        assertThat(body(getAs(director, "/api/v1/contracts?childId=" + noa)).get(0).at("/versions/0/billingMode").asText())
                .isEqualTo("REEL");
        assertThat(body(getAs(director, "/api/v1/contracts?childId=" + lea)).get(0).at("/versions/0/billingMode").asText())
                .as("contrats existants inchangés").isEqualTo("MENSUALISE");
    }

    @Test
    void uneFactureEmiseEstImmuable() throws Exception {
        generated();
        var id = invoiceOf("Famille Wamytan");
        assertThatThrownBy(() -> jdbc.update("UPDATE invoice SET family_total = 0 WHERE id = ?::uuid", id))
                .hasMessageContaining("émise");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM invoice_line WHERE invoice_id = ?::uuid", id))
                .hasMessageContaining("émise");
    }

    @Test
    void avoirTotalPuisRefacturationDuMois() throws Exception {
        aidsForLea();
        generated();
        var id = invoiceOf("Famille Wamytan");
        long total = body(getAs(director, "/api/v1/billing/invoices/" + id)).at("/summary/familyTotal").asLong();

        var tooMuch = perform(post("/api/v1/billing/invoices/" + id + "/credit-notes"), director, Map.of(
                "reason", "Geste commercial", "full", false,
                "lines", List.of(Map.of("label", "Remise", "amount", total + 1))));
        assertThat(status(tooMuch)).isEqualTo(422);
        assertThat(body(tooMuch).get("code").asText()).isEqualTo("credit_note_too_high");

        var partial = perform(post("/api/v1/billing/invoices/" + id + "/credit-notes"), director, Map.of(
                "reason", "Journée de fermeture non prévue", "full", false,
                "lines", List.of(Map.of("label", "Fermeture du 12", "amount", 2_000))));
        assertThat(status(partial)).isEqualTo(201);
        assertThat(body(partial).get("number").asText()).isEqualTo(number("AV", 1));
        var afterPartial = body(getAs(director, "/api/v1/billing/invoices/" + id));
        assertThat(afterPartial.at("/summary/remaining").asLong()).isEqualTo(total - 2_000);
        assertThat(afterPartial.at("/summary/status").asText()).isEqualTo("PARTIELLE");

        var full = perform(post("/api/v1/billing/invoices/" + id + "/credit-notes"), director,
                Map.of("reason", "Erreur de planning", "full", true));
        assertThat(status(full)).as(full.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(full).get("number").asText()).isEqualTo(number("AV", 2));
        assertThat(body(full).get("familyTotal").asLong()).isEqualTo(total - 2_000);
        var cancelled = body(getAs(director, "/api/v1/billing/invoices/" + id));
        assertThat(cancelled.at("/summary/status").asText()).isEqualTo("ANNULEE");
        assertThat(cancelled.at("/summary/remaining").asLong()).isZero();
        assertThat(body(getAs(director, "/api/v1/billing/funder-requests?month=" + MONTH)))
                .as("les demandes non envoyées perdent les aides de la facture annulée").isEmpty();
        assertThat(status(perform(post("/api/v1/billing/invoices/" + id + "/credit-notes"), director,
                Map.of("reason", "Encore", "full", true)))).isEqualTo(409);

        assertThat(row(preview(), "Famille Wamytan").get("status").asText()).isEqualTo("PRETE");
        var redo = generated();
        assertThat(redo.get("firstNumber").asText()).isEqualTo(number("FA", 3));
        assertThat(redo.get("funderRequests")).hasSize(2);
        assertThat(auditCount("CREDIT_NOTE_ISSUED")).isEqualTo(2);
    }

    @Test
    void uneDemandeDejaEnvoyeeNEstReclameeQuUneFois() throws Exception {
        aidsForLea();
        generated();
        for (var request : body(getAs(director, "/api/v1/billing/funder-requests?month=" + MONTH))) {
            var sent = perform(post("/api/v1/billing/funder-requests/" + request.get("id").asText() + "/sent"),
                    director, Map.of("sentOn", TODAY.toString()));
            assertThat(body(sent).get("status").asText()).isEqualTo("ENVOYEE");
        }
        var id = invoiceOf("Famille Wamytan");
        perform(post("/api/v1/billing/invoices/" + id + "/credit-notes"), director,
                Map.of("reason", "Erreur d'adresse", "full", true));
        var redo = generated();
        assertThat(redo.get("funderRequests")).as("aides inchangées : rien de plus à réclamer").isEmpty();
        assertThat(body(getAs(director, "/api/v1/billing/funder-requests?month=" + MONTH))).hasSize(2);
    }

    @Test
    void envoiNoteALaMainEtHistorique() throws Exception {
        generated();
        var id = invoiceOf("Famille Wamytan");
        var future = perform(post("/api/v1/billing/invoices/" + id + "/sent"), director,
                Map.of("sentOn", TODAY.plusDays(1).toString()));
        assertThat(status(future)).isEqualTo(422);
        var sent = body(perform(post("/api/v1/billing/invoices/" + id + "/sent"), director,
                Map.of("sentOn", TODAY.toString(), "channel", "remise en main propre")));
        assertThat(sent.at("/summary/sentOn").asText()).isEqualTo(TODAY.toString());
        assertThat(sent.get("events")).extracting(e -> e.get("label").asText())
                .contains("Notée envoyée le %s (remise en main propre)".formatted(nc.ticagou.common.Formats.date(TODAY)));
    }

    @Test
    void droitsParRole() throws Exception {
        createUser(director, "COMPTABILITE", "compta", "Comptable-2026!", "4821");
        createUser(director, "EDUCATRICE", "educ", "Educatrice-2026!", "4822");
        createUser(director, "CUISINE", "cuisine", "Cuisine-2026!!", "4823");
        var compta = accessToken("compta", "Comptable-2026!");
        var educ = accessToken("educ", "Educatrice-2026!");
        var cuisine = accessToken("cuisine", "Cuisine-2026!!");
        var path = "/api/v1/billing/months/" + MONTH + "/preview";
        assertThat(status(getAs(compta, path))).isEqualTo(200);
        assertThat(status(getAs(educ, path))).isEqualTo(403);
        assertThat(status(getAs(cuisine, path))).isEqualTo(403);
        assertThat(status(getAs(educ, "/api/v1/billing/funders"))).isEqualTo(403);
        assertThat(status(getAs(educ, "/api/v1/billing/absence-reasons"))).isEqualTo(200);
        assertThat(status(getAs(cuisine, "/api/v1/billing/absence-reasons"))).isEqualTo(403);
        assertThat(status(perform(post("/api/v1/billing/months/" + MONTH + "/generate"), compta, Map.of())))
                .isEqualTo(201);
    }

    @Test
    void piecesEnPdfEtExportExcel() throws Exception {
        aidsForLea();
        generated();
        var id = invoiceOf("Famille Wamytan");
        assertThat(pdf("/api/v1/billing/invoices/" + id + "/pdf")).startsWith("%PDF");
        var note = body(perform(post("/api/v1/billing/invoices/" + id + "/credit-notes"), director, Map.of(
                "reason", "Geste", "full", false, "lines", List.of(Map.of("label", "Remise", "amount", 500)))));
        assertThat(pdf("/api/v1/billing/credit-notes/" + note.get("id").asText() + "/pdf")).startsWith("%PDF");
        var request = body(getAs(director, "/api/v1/billing/funder-requests?month=" + MONTH)).get(0);
        assertThat(request.get("lines")).hasSize(1);
        assertThat(request.at("/lines/0/beneficiary").asText()).isEqualTo("Sarah Wamytan");
        assertThat(pdf("/api/v1/billing/funder-requests/" + request.get("id").asText() + "/pdf")).startsWith("%PDF");
        var xlsx = getAs(director, "/api/v1/billing/funder-requests/export?month=" + MONTH);
        assertThat(status(xlsx)).isEqualTo(200);
        assertThat(xlsx.getResponse().getContentAsByteArray()).startsWith((byte) 'P', (byte) 'K');
        assertThat(auditCount("FUNDER_REQUEST_EXPORTED")).isEqualTo(1);
    }

    @Test
    void parametresDatesEtTgc() throws Exception {
        var rules = body(getAs(director, "/api/v1/billing/rules"));
        assertThat(rules).hasSize(1);
        assertThat(rules.at("/0/overtimeFee").asLong()).isEqualTo(1_000);
        var overlap = new LinkedHashMap<String, Object>();
        overlap.put("validFrom", MONTH.atDay(1).toString());
        overlap.put("extraRateMode", "CONTRAT");
        overlap.put("extraToleranceMinutes", 10);
        overlap.put("extraRoundingMinutes", 15);
        overlap.put("overtimeFee", 1_500);
        overlap.put("overtimeToleranceMinutes", 10);
        overlap.put("sickWaitingDays", 1);
        overlap.put("leaveNoticeDays", 15);
        overlap.put("paymentDays", 10);
        overlap.put("totalRoundingStep", 1);
        overlap.put("totalRoundingMode", "PROCHE");
        var refused = perform(post("/api/v1/billing/rules"), director, overlap);
        assertThat(status(refused)).isEqualTo(422);
        assertThat(body(refused).get("code").asText()).isEqualTo("billing_rule_overlap");

        assertThat(body(getAs(director, "/api/v1/billing/absence-reasons"))).hasSize(6);
        var forfaitRate = body(getAs(director, "/api/v1/billing/tgc-rates")).findValues("service").stream()
                .filter(s -> s.asText().equals("FORFAIT")).count();
        assertThat(forfaitRate).isEqualTo(1);
        String forfaitId = null;
        for (var rate : body(getAs(director, "/api/v1/billing/tgc-rates"))) {
            if (rate.get("service").asText().equals("FORFAIT")) {
                forfaitId = rate.get("id").asText();
                assertThat(rate.get("exempt").asBoolean()).isTrue();
            }
        }
        var tgc = perform(put("/api/v1/billing/tgc-rates/" + forfaitId), director, Map.of("service", "FORFAIT",
                "rate", 60, "exempt", false, "validFrom", MONTH.minusYears(1).withMonth(1).atDay(1).toString()));
        assertThat(status(tgc)).as(tgc.getResponse().getContentAsString()).isEqualTo(200);
        var wamytan = row(preview(), "Famille Wamytan");
        long forfait = monthlyFee(lea);
        assertThat(wamytan.get("tgc").asLong()).isEqualTo((forfait * 6 + 50) / 100);
        assertThat(wamytan.get("familyTotal").asLong()).isEqualTo(forfait + (forfait * 6 + 50) / 100);
    }

    @Test
    void laPurgeEstRefuseeQuandIlYADesFactures() throws Exception {
        generated();
        var family = jdbc.queryForObject("SELECT family_id::text FROM child WHERE id = ?::uuid", String.class, lea);
        var result = perform(post("/api/v1/families/" + family + "/purge"), director,
                Map.of("confirmation", "Famille Wamytan"));
        assertThat(status(result)).isEqualTo(422);
        assertThat(body(result).get("code").asText()).isEqualTo("family_has_invoices");
        var export = getAs(director, "/api/v1/families/" + family + "/export");
        assertThat(status(export)).isEqualTo(200);
    }

    String pdf(String path) throws Exception {
        var result = getAs(director, path);
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return new String(result.getResponse().getContentAsByteArray(), 0, 4, StandardCharsets.ISO_8859_1);
    }
}
