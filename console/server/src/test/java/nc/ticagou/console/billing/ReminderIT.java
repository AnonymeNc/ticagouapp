package nc.ticagou.console.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.mail.Multipart;
import nc.ticagou.console.auth.CurrentOperator;
import nc.ticagou.console.parc.ParcRepository;

/** Relances préparées (phase 4) : regroupement par client, unicité, vérification au clic, règles et fériés. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(BillingIT.Config.class)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"console.scheduling=false", "console.secret-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "console.mail-from=Ti'Cagou <facturation@ticagou.nc>"})
class ReminderIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired BillingIT.MovableClock clock;
    @Autowired BillingIT.FakeMail mail;
    @Autowired ParcRepository parc;
    @Autowired InvoiceService invoices;
    @Autowired ReminderService reminders;

    MockHttpSession session;
    UUID clientId;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE reminder, outbox_message, payment_allocation, payment, invoice_line, invoice, subscription_contract, counter, "
                + "creche_note, creche_snapshot, alert_history, creche, client, audit_event CASCADE");
        jdbc.update("DELETE FROM reminder_rule_set WHERE id <> '00000000-0000-0000-0000-00000000a001'");
        jdbc.update("UPDATE reminder_rule_set SET valid_to = NULL");
        jdbc.update("DELETE FROM public_holiday WHERE day > DATE '2027-12-31'");
        jdbc.update("""
                UPDATE issuer SET name = 'Ti''Cagou', ridet = '1234567.001', address = 'Nouméa', bank = 'BCI', iban = 'NC00 1',
                    copy_email = 'facturation@ticagou.nc'""");
        clock.now = BillingIT.START;
        mail.sent.clear();
        mail.down = false;
        session = new MockHttpSession();
        parc.ensure("lagon-bleu", "Micro-crèche Lagon Bleu", "CLIENT", clock.instant());
        jdbc.update("UPDATE client SET name = 'SARL Lagon Bleu', billing_email = 'compta@lagon-bleu.nc', director_name = 'Madame Wamytan'");
        clientId = jdbc.queryForObject("SELECT id FROM client", UUID.class);
    }

    MvcResult call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        var principal = new CurrentOperator.Principal(UUID.randomUUID(), "jordan", "Jordan Moniot");
        request.with(authentication(new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")))))
                .with(csrf()).session(session);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        }
        return mvc.perform(request).andReturn();
    }

    JsonNode ok(MockHttpServletRequestBuilder request, Object body) throws Exception {
        var result = call(request, body);
        var text = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as(text).isBetween(200, 299);
        return text.isEmpty() ? null : json.readTree(text);
    }

    JsonNode problem(MockHttpServletRequestBuilder request, Object body, int status) throws Exception {
        var result = call(request, body);
        var text = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as(text).isEqualTo(status);
        return json.readTree(text);
    }

    /** Facture validée le jour donné (échéance à 30 jours). */
    InvoiceService.Detail invoice(String issuedOn, long amount) {
        clock.on(issuedOn);
        var draft = invoices.createDraft(new InvoiceService.DraftInput("lagon-bleu", "Formation", null, null, null,
                List.of(new InvoiceService.LineInput("Formation équipe", null, 1, amount, "PRESTATION"))));
        return invoices.validate(draft.summary().id());
    }

    @Test
    void unMessageParClientUneRelanceParNiveauEtVerificationAuClic() throws Exception {
        var first = invoice("2026-09-23", 100_000);   // échéance 23/10
        var second = invoice("2026-10-02", 50_000);   // échéance 01/11
        jdbc.update("UPDATE outbox_message SET status = 'ECARTE'"); // e-mails des factures : hors sujet ici

        clock.on("2026-11-06"); // vendredi : F1 à J+14, F2 à J+5 → F1 en relance 1
        assertThat(reminders.prepare(LocalDate.of(2026, 11, 6))).isEqualTo(1);
        clock.on("2026-11-09"); // lundi : F1 J+17 (relance 2, J+15 tombé samedi), F2 J+8 (relance 1, J+7 tombé dimanche)
        assertThat(reminders.prepare(LocalDate.of(2026, 11, 9))).isEqualTo(1);
        assertThat(reminders.prepare(LocalDate.of(2026, 11, 9))).as("idempotent").isZero();

        var outbox = ok(get("/api/v1/envoi").param("statut", "PREPARE"), null);
        assertThat(outbox.get("pending").asInt()).isEqualTo(2);
        var grouped = outbox.at("/messages/0");
        assertThat(grouped.get("reminderLevel").asInt()).isEqualTo(2);
        assertThat(grouped.get("subject").asText()).isEqualTo("Deuxième relance — facture F2026-0001, F2026-0002");
        assertThat(grouped.get("cc").asText()).as("relance 2 : copie à l'éditeur").isEqualTo("facturation@ticagou.nc");
        assertThat(grouped.get("to").asText()).isEqualTo("compta@lagon-bleu.nc");
        assertThat(grouped.get("body").asText()).startsWith("Bonjour Madame Wamytan,")
                .contains("- facture F2026-0001 du 23/09/2026, échue le 23/10/2026 : 111 000 F restants",
                        "- facture F2026-0002 du 02/10/2026", "Total restant : 166 500 F, en retard de 17 jours");
        assertThat(grouped.get("attachments").size()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT level FROM reminder WHERE invoice_id = ? ORDER BY level", Integer.class, first.summary().id()))
                .containsExactly(1, 2);
        var detail = ok(get("/api/v1/factures/" + second.summary().id()), null);
        assertThat(detail.at("/reminders/0/label").asText()).isEqualTo("Relance 1");
        assertThat(detail.get("nextReminderOn").asText()).as("relance 2 de F2 : J+15 = 16/11").isEqualTo("2026-11-16");

        // F1 soldée avant le clic : la relance groupée est retirée, F2 sera relancée seule à la prochaine préparation.
        ok(post("/api/v1/paiements"), Map.of("crecheSlug", "lagon-bleu", "receivedOn", "2026-11-09", "amount", 111_000, "method", "VIREMENT",
                "allocations", List.of(Map.of("invoiceId", first.summary().id().toString(), "amount", 111_000))));
        var result = ok(post("/api/v1/envoi/envoyer"), Map.of("ids", List.of(grouped.get("id").asText())));
        assertThat(result.at("/0/sent").asBoolean()).isFalse();
        assertThat(result.at("/0/error").asText()).contains("Soldée entre-temps : F2026-0001");
        assertThat(mail.sent).isEmpty();
        assertThat(reminders.prepare(LocalDate.of(2026, 11, 9))).isEqualTo(1);
        var alone = ok(get("/api/v1/envoi").param("statut", "PREPARE"), null).at("/messages/0");
        assertThat(alone.get("subject").asText()).isEqualTo("Rappel de paiement — facture F2026-0002");
        assertThat(alone.get("cc").isNull()).as("relance 1 : pas de copie").isTrue();

        ok(post("/api/v1/envoi/envoyer"), Map.of("ids", List.of(alone.get("id").asText())));
        assertThat(mail.sent).hasSize(1);
        assertThat(((Multipart) mail.sent.getFirst().getContent()).getBodyPart(1).getFileName()).isEqualTo("F2026-0002.pdf");
        assertThat(jdbc.queryForList("SELECT action FROM audit_event", String.class)).contains("RELANCE_PREPAREE", "RELANCE_RETIREE", "ENVOI");
    }

    @Test
    void derniereRelanceEnvoyeeSuspensionProposeeJamaisAppliquee() throws Exception {
        var f = invoice("2026-09-01", 20_000); // échéance 01/10
        jdbc.update("UPDATE outbox_message SET status = 'ECARTE'");
        clock.on("2026-11-02");
        assertThat(reminders.prepare(LocalDate.of(2026, 11, 2))).isEqualTo(1);
        var message = ok(get("/api/v1/envoi").param("statut", "PREPARE"), null).at("/messages/0");
        assertThat(message.get("reminderLevel").asInt()).as("rattrapage : directement la relance 3").isEqualTo(3);
        assertThat(ok(get("/api/v1/tableau"), null).get("suspensions").size()).as("pas encore envoyée").isZero();
        ok(post("/api/v1/envoi/envoyer"), Map.of("ids", List.of(message.get("id").asText())));
        var board = ok(get("/api/v1/tableau"), null);
        assertThat(board.at("/suspensions/0/number").asText()).isEqualTo(f.summary().number());
        assertThat(jdbc.queryForObject("SELECT lifecycle FROM creche", String.class)).as("rien n'est suspendu seul").isEqualTo("CLIENT");
    }

    @Test
    void clientEnPauseOuDecaleEtFactureSuspendue() throws Exception {
        var f = invoice("2026-09-23", 10_000); // échéance 23/10
        clock.on("2026-11-09");
        assertThat(problem(put("/api/v1/relances/clients/" + clientId), Map.of("shiftDays", 0, "paused", true), 422).get("code").asText())
                .isEqualTo("motif");
        ok(put("/api/v1/relances/clients/" + clientId), Map.of("shiftDays", 0, "paused", true, "note", "Échéancier accordé jusqu’au 15/12"));
        assertThat(reminders.prepare(LocalDate.of(2026, 11, 9))).isZero();

        ok(put("/api/v1/relances/clients/" + clientId), Map.of("shiftDays", 15, "paused", false, "note", "Mandat administratif"));
        // Décalé de 15 jours : relance 1 à J+22 = samedi 14/11, donc lundi 16/11 (au lieu du 30/10) ; relance 2 pas encore.
        var settings = ok(get("/api/v1/relances"), null);
        assertThat(settings.at("/clients/0/shiftDays").asInt()).isEqualTo(15);
        assertThat(reminders.prepare(LocalDate.of(2026, 11, 9))).isZero();
        clock.on("2026-11-16");
        assertThat(reminders.prepare(LocalDate.of(2026, 11, 16))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT level FROM reminder", Integer.class)).isEqualTo(1);

        ok(post("/api/v1/factures/" + f.summary().id() + "/relances"), Map.of("paused", true));
        clock.on("2026-12-01");
        assertThat(reminders.prepare(LocalDate.of(2026, 12, 1))).as("facture aux relances suspendues").isZero();
    }

    @Test
    void reglesDateesEtFeries() throws Exception {
        var settings = ok(get("/api/v1/relances"), null);
        var setId = settings.at("/sets/0/id").asText();
        assertThat(settings.at("/rules/" + setId).size()).isEqualTo(4);
        assertThat(settings.get("variables").toString()).contains("factures", "total");
        var rules = new ArrayList<Map<String, Object>>();
        settings.at("/rules/" + setId).forEach(r -> {
            var m = new HashMap<String, Object>();
            r.fieldNames().forEachRemaining(f -> m.put(f, r.get(f).isBoolean() ? r.get(f).asBoolean() : r.get(f).isNumber() ? r.get(f).asInt() : r.get(f).asText()));
            if ((int) m.get("level") == 0) {
                m.put("active", true);
            }
            rules.add(m);
        });
        var version = new HashMap<String, Object>(Map.of("label", "Relances 2027", "validFrom", "2027-01-01", "rules", rules));
        var updated = ok(post("/api/v1/relances"), version);
        assertThat(updated.at("/sets/0/label").asText()).isEqualTo("Relances 2027");
        assertThat(updated.at("/sets/1/validTo").asText()).isEqualTo("2026-12-31");
        assertThat(problem(post("/api/v1/relances"), version, 422).get("code").asText()).isEqualTo("relances_date");

        var bad = new ArrayList<>(rules);
        bad.removeLast();
        version.put("validFrom", "2027-06-01");
        version.put("rules", bad);
        assertThat(problem(post("/api/v1/relances"), version, 422).get("code").asText()).isEqualTo("relances_niveaux");

        ok(post("/api/v1/relances/feries"), Map.of("day", "2028-01-01", "label", "Jour de l’an"));
        assertThat(ok(get("/api/v1/relances"), null).get("holidays").toString()).contains("2028-01-01");
        ok(delete("/api/v1/relances/feries/2028-01-01"), null);
        assertThat(ok(get("/api/v1/relances"), null).get("holidays").toString()).doesNotContain("2028-01-01");
        assertThat(ok(post("/api/v1/relances/preparer"), null).get("messages").asInt()).isZero();
    }
}
