package nc.ticagou.console.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import nc.ticagou.console.auth.CurrentOperator;
import nc.ticagou.console.parc.ParcRepository;

/**
 * Facturation de l'éditeur : contrat, brouillon de renouvellement, validation (code redemandé, mentions, numéro, PDF,
 * e-mail préparé), immutabilité, numérotation sans trou sous concurrence, paiements, retards, avoirs, avenant au
 * prorata, boîte d'envoi, paramètres.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(BillingIT.Config.class)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"console.scheduling=false", "console.secret-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "console.mail-from=Ti'Cagou <facturation@ticagou.nc>"})
public class BillingIT {

    /** 08/11/2026 à 09:00, heure de Nouméa. */
    public static final Instant START = Instant.parse("2026-11-07T22:00:00Z");

    public static final class MovableClock extends Clock {
        public Instant now = START;

        public void on(String day) {
            now = LocalDate.parse(day).atTime(9, 0).atZone(ZoneId.of("Pacific/Noumea")).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Pacific/Noumea");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Serveur de messagerie factice : garde les messages, ou refuse tout. */
    public static final class FakeMail implements JavaMailSender {
        final List<MimeMessage> sent = new CopyOnWriteArrayList<>();
        volatile boolean down;

        @Override
        public MimeMessage createMimeMessage() {
            return new MimeMessage(Session.getInstance(new Properties()));
        }

        @Override
        public MimeMessage createMimeMessage(InputStream contentStream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void send(MimeMessage... messages) {
            if (down) {
                throw new MailSendException("Connexion refusée");
            }
            sent.addAll(List.of(messages));
        }

        @Override
        public void send(SimpleMailMessage... simpleMessages) {
            throw new UnsupportedOperationException();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:17-alpine");
        }

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }

        @Bean
        @Primary
        FakeMail fakeMail() {
            return new FakeMail();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MovableClock clock;
    @Autowired FakeMail mail;
    @Autowired ParcRepository parc;
    @Autowired InvoiceService invoices;
    @Autowired ContractService contracts;

    MockHttpSession session;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE outbox_message, payment_allocation, payment, invoice_line, invoice, subscription_contract, counter, "
                + "creche_note, creche_snapshot, alert_history, creche, client, audit_event CASCADE");
        jdbc.update("UPDATE issuer SET name = NULL, ridet = NULL, address = NULL, iban = NULL, bank = NULL, copy_email = NULL");
        // Barèmes de la migration : 2026 (jusqu'au 31/10/2026, V9), puis formule unique (V9).
        jdbc.update("DELETE FROM price_list WHERE id NOT IN ('00000000-0000-0000-0000-000000002026', '00000000-0000-0000-0000-000000202611')");
        jdbc.update("UPDATE price_list SET valid_to = CASE WHEN id = '00000000-0000-0000-0000-000000002026' THEN DATE '2026-10-31' END");
        jdbc.update("DELETE FROM tgc_rate WHERE valid_from > DATE '2026-01-01'");
        jdbc.update("UPDATE tgc_rate SET valid_to = NULL");
        clock.now = START;
        mail.sent.clear();
        mail.down = false;
        session = new MockHttpSession();
        parc.ensure("lagon-bleu", "Micro-crèche Lagon Bleu", "PROSPECT", clock.instant());
        jdbc.update("UPDATE client SET name = 'SARL Lagon Bleu', billing_email = 'compta@lagon-bleu.nc', director_name = 'Madame Wamytan'");
    }

    // --- Outils

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
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isBetween(200, 299);
        var text = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.isEmpty() ? null : json.readTree(text);
    }

    JsonNode problem(MockHttpServletRequestBuilder request, Object body, int status) throws Exception {
        var result = call(request, body);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(status);
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Code saisi à l'instant (action sensible). */
    void confirmed() {
        session.setAttribute("console.confirmedAt", clock.instant());
    }

    void mentions() throws Exception {
        ok(put("/api/v1/editeur"), Map.of("name", "Ti'Cagou — Jordan Moniot", "legalForm", "Entreprise individuelle", "ridet", "1234567.001",
                "address", "Nouméa", "bank", "BCI", "iban", "NC00 1234 5678 9012 3456 7890 123", "copyEmail", "facturation@ticagou.nc",
                "latePenalties", "Pénalités de retard : taux légal."));
    }

    /** Contrat à la formule unique (ADR 0021) : seules changent les places et les options. */
    static Map<String, Object> contract(int places, int extraTablets, boolean premiumSupport, String startsOn) {
        var c = new HashMap<String, Object>();
        c.put("plan", "COMPLET");
        c.put("places", places);
        c.put("modules", List.of());
        c.put("extraDesktops", 0);
        c.put("extraTablets", extraTablets);
        c.put("premiumSupport", premiumSupport);
        c.put("engagementYears", 1);
        c.put("rhythm", "ANNUEL");
        c.put("extraStructure", false);
        c.put("freeDiscount", 0);
        c.put("startsOn", startsOn);
        c.put("signedOn", "2026-10-15");
        return c;
    }

    /** Ti'Cagou Micro + 2 tablettes au 01/11/2026 et sa première facture annuelle, validée. */
    JsonNode subscribedAndInvoiced() throws Exception {
        ok(put("/api/v1/parc/lagon-bleu/contrat"), contract(12, 2, false, "2026-11-01"));
        var draft = ok(post("/api/v1/parc/lagon-bleu/contrat/prochaine-facture"), null);
        mentions();
        confirmed();
        return ok(post("/api/v1/factures/" + draft.at("/summary/id").asText() + "/valider"), null);
    }

    // --- Scénarios

    @Test
    void contratBrouillonPuisValidationAvecCodeMentionsNumeroEtPdf() throws Exception {
        // Ti'Cagou Micro 18 500 F + 2 tablettes à 1 000 F, par mois.
        var saved = ok(put("/api/v1/parc/lagon-bleu/contrat"), contract(12, 2, false, "2026-11-01"));
        assertThat(saved.at("/contract/monthly/total").asLong()).isEqualTo(20_500);
        assertThat(saved.at("/contract/period/total").asLong()).isEqualTo(246_000);
        assertThat(saved.at("/contract/priceListLabel").asText()).isEqualTo("Barème 2026");
        assertThat(saved.at("/contract/expected/desktops").asInt()).isEqualTo(1);
        assertThat(saved.at("/contract/expected/tablets").asInt()).isEqualTo(4);
        assertThat(saved.at("/contract/next/from").asText()).isEqualTo("2026-11-01");
        assertThat(jdbc.queryForObject("SELECT lifecycle FROM creche", String.class)).as("le prospect devient client").isEqualTo("CLIENT");

        var draft = ok(post("/api/v1/parc/lagon-bleu/contrat/prochaine-facture"), null);
        var id = draft.at("/summary/id").asText();
        assertThat(draft.at("/summary/subject").asText()).isEqualTo("Abonnement 11/2026 – 10/2027");
        assertThat(draft.at("/summary/state").asText()).isEqualTo("BROUILLON");
        assertThat(draft.at("/summary/totalHt").asLong()).isEqualTo(246_000);
        assertThat(draft.at("/summary/totalTgc").asLong()).isEqualTo(27_060);
        assertThat(draft.at("/summary/totalTtc").asLong()).isEqualTo(273_060);
        assertThat(draft.at("/lines/0/label").asText()).isEqualTo("Ti'Cagou Micro");
        assertThat(draft.at("/lines/1/label").asText()).isEqualTo("Tablette supplémentaire (2)");
        assertThat(draft.at("/lines/0/detail").asText()).startsWith("01/11/2026 – 31/10/2027");
        problem(post("/api/v1/parc/lagon-bleu/contrat/prochaine-facture"), null, 201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoice", Integer.class)).as("période suivante, pas de doublon").isEqualTo(2);
        assertThat(problem(get("/api/v1/factures/" + id + "/pdf"), null, 422).get("code").asText()).isEqualTo("brouillon");

        assertThat(problem(post("/api/v1/factures/" + id + "/valider"), null, 403).get("code").asText()).isEqualTo("confirmation_required");
        confirmed();
        var missing = problem(post("/api/v1/factures/" + id + "/valider"), null, 422);
        assertThat(missing.get("detail").asText()).contains("raison sociale", "RIDET", "adresse", "coordonnées bancaires");
        mentions();

        var validated = ok(post("/api/v1/factures/" + id + "/valider"), null);
        assertThat(validated.at("/summary/number").asText()).isEqualTo("F2026-0001");
        assertThat(validated.at("/summary/issuedOn").asText()).isEqualTo("2026-11-08");
        assertThat(validated.at("/summary/dueOn").asText()).as("30 jours").isEqualTo("2026-12-08");
        assertThat(validated.at("/summary/state").asText()).isEqualTo("A_ENVOYER");
        assertThat(validated.at("/issuer/ridet").asText()).isEqualTo("1234567.001");
        assertThat(validated.at("/client/name").asText()).isEqualTo("SARL Lagon Bleu");

        var pdf = call(get("/api/v1/factures/" + id + "/pdf"), null).getResponse();
        assertThat(pdf.getContentType()).isEqualTo("application/pdf");
        assertThat(new String(pdf.getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(validated.at("/pdfSha256").asText()).isEqualTo(InvoicePdf.sha256(pdf.getContentAsByteArray()));

        var messages = ok(get("/api/v1/envoi"), null);
        assertThat(messages.get("pending").asInt()).isEqualTo(1);
        var message = messages.at("/messages/0");
        assertThat(message.get("to").asText()).isEqualTo("compta@lagon-bleu.nc");
        assertThat(message.get("cc").asText()).isEqualTo("facturation@ticagou.nc");
        assertThat(message.get("subject").asText()).isEqualTo("Votre facture F2026-0001");
        assertThat(message.get("body").asText()).startsWith("Bonjour Madame Wamytan,")
                .contains("273 060 F TTC", "avant le 08/12/2026", "IBAN NC00");
        assertThat(mail.sent).as("rien ne part sans clic").isEmpty();
        assertThat(jdbc.queryForList("SELECT action FROM audit_event ORDER BY at, id", String.class)).contains("CONTRAT_SIGNE",
                "RENOUVELLEMENT_PREPARE", "EDITEUR_MODIFIE", "FACTURE_VALIDEE");
    }

    @Test
    void uneSeuleFormuleSansModuleALaCarte() throws Exception {
        // ADR 0021 : ni ancienne formule ni module ajouté pour un nouveau contrat ou un avenant.
        var gestion = new HashMap<>(contract(12, 0, false, "2026-11-01"));
        gestion.put("plan", "GESTION");
        assertThat(problem(put("/api/v1/parc/lagon-bleu/contrat"), gestion, 400).toString()).contains("Une seule formule");
        var withModule = new HashMap<>(contract(12, 0, false, "2026-11-01"));
        withModule.put("modules", List.of("REPAS"));
        assertThat(problem(put("/api/v1/parc/lagon-bleu/contrat"), withModule, 422).get("code").asText()).isEqualTo("module");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription_contract", Integer.class)).isZero();
    }

    @Test
    void factureValideeImmuableEnBaseCommeDansLApi() throws Exception {
        var invoice = subscribedAndInvoiced();
        var id = invoice.at("/summary/id").asText();
        assertThatThrownBy(() -> jdbc.update("UPDATE invoice SET total_ttc = 1 WHERE id = ?::uuid", id)).hasMessageContaining("ne se modifie plus");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM invoice WHERE id = ?::uuid", id)).hasMessageContaining("ne se supprime pas");
        assertThatThrownBy(() -> jdbc.update("UPDATE invoice_line SET amount = 1 WHERE invoice_id = ?::uuid", id))
                .hasMessageContaining("ne changent plus");
        jdbc.update("UPDATE invoice SET sent_at = now(), reminders_paused = true WHERE id = ?::uuid", id);
        var draft = Map.of("crecheSlug", "lagon-bleu", "subject", "Essai", "lines",
                List.of(Map.of("label", "Formation", "quantity", 1, "unitPrice", 15000, "tgcNature", "PRESTATION")));
        assertThat(problem(put("/api/v1/factures/" + id), draft, 422).get("code").asText()).isEqualTo("facture_validee");
        assertThat(problem(delete("/api/v1/factures/" + id), null, 422).get("code").asText()).isEqualTo("facture_validee");
        assertThat(problem(post("/api/v1/factures/" + id + "/valider"), null, 422).get("code").asText()).isEqualTo("deja_validee");
    }

    @Test
    void numerotationContinueSansTrouSousConcurrence() throws Exception {
        mentions();
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 6; i++) {
            ids.add(invoices.createDraft(new InvoiceService.DraftInput("lagon-bleu", "Formation " + i, null, null, null,
                    List.of(new InvoiceService.LineInput("Formation équipe", null, 1, 15_000, "PRESTATION")))).summary().id());
        }
        // Un brouillon refusé (total nul) ne consomme pas de numéro.
        var empty = invoices.createDraft(new InvoiceService.DraftInput("lagon-bleu", "Gratuit", null, null, null,
                List.of(new InvoiceService.LineInput("Geste commercial", null, 1, 0, "PRESTATION")))).summary().id();
        var pool = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (var id : ids) {
                futures.add(pool.submit(() -> invoices.validate(id).summary().number()));
            }
            futures.add(pool.submit(() -> {
                try {
                    return invoices.validate(empty).summary().number();
                } catch (RuntimeException e) {
                    return "refusée";
                }
            }));
            var numbers = new ArrayList<String>();
            for (var f : futures) {
                numbers.add(f.get());
            }
            assertThat(numbers).contains("refusée");
            numbers.remove("refusée");
            assertThat(numbers).containsExactlyInAnyOrder("F2026-0001", "F2026-0002", "F2026-0003", "F2026-0004", "F2026-0005", "F2026-0006");
        } finally {
            pool.shutdown();
        }
        assertThat(jdbc.queryForObject("SELECT value FROM counter WHERE series = 'F' AND year = 2026", Integer.class)).isEqualTo(6);
        clock.on("2027-01-05");
        var next = invoices.createDraft(new InvoiceService.DraftInput("lagon-bleu", "Janvier", null, null, null,
                List.of(new InvoiceService.LineInput("Formation", null, 1, 15_000, "PRESTATION")))).summary().id();
        assertThat(invoices.validate(next).summary().number()).as("nouvel exercice").isEqualTo("F2027-0001");
    }

    @Test
    void paiementsPartielsTropPercuEtRetard() throws Exception {
        var invoice = subscribedAndInvoiced();
        var id = invoice.at("/summary/id").asText();
        clock.on("2026-11-20");
        var payment = ok(post("/api/v1/paiements"), Map.of("crecheSlug", "lagon-bleu", "receivedOn", "2026-11-20", "amount", 4_860,
                "method", "VIREMENT", "reference", "VIR 4860"));
        assertThat(payment.at("/allocations/0/invoiceNumber").asText()).as("affecté à la plus ancienne").isEqualTo("F2026-0001");
        assertThat(ok(get("/api/v1/factures/" + id), null).at("/summary/remaining").asLong()).isEqualTo(268_200);

        clock.on("2026-12-24");
        var late = ok(get("/api/v1/factures/" + id), null).get("summary");
        assertThat(late.get("state").asText()).isEqualTo("RETARD");
        assertThat(late.get("daysLate").asLong()).isEqualTo(16);
        var row = ok(get("/api/v1/parc"), null);
        assertThat(row.at("/creches/0/balanceDue").asLong()).isEqualTo(268_200);
        assertThat(row.at("/creches/0/daysLate").asLong()).isEqualTo(16);
        assertThat(row.at("/creches/0/health/reasons").toString()).contains("Impayé, J+16");
        assertThat(row.at("/summary/unpaid").asInt()).isEqualTo(1);
        var board = ok(get("/api/v1/tableau"), null);
        assertThat(board.get("mrr").asLong()).as("Ti'Cagou Micro + 2 tablettes").isEqualTo(20_500);
        assertThat(board.get("outstanding").asLong()).isEqualTo(268_200);
        assertThat(board.get("overdue").asLong()).isEqualTo(268_200);
        assertThat(board.at("/months/5/month").asText()).isEqualTo("2026-12");
        assertThat(board.at("/months/4/invoiced").asLong()).as("novembre").isEqualTo(273_060);
        assertThat(board.at("/months/4/collected").asLong()).isEqualTo(4_860);

        assertThat(problem(post("/api/v1/paiements"), Map.of("crecheSlug", "lagon-bleu", "receivedOn", "2026-12-24", "amount", 300_000,
                "method", "CHEQUE", "allocations", List.of(Map.of("invoiceId", id, "amount", 300_000))), 422).get("detail").asText())
                .contains("268 200 F restent dus");
        var over = ok(post("/api/v1/paiements"), Map.of("crecheSlug", "lagon-bleu", "receivedOn", "2026-12-24", "amount", 280_000,
                "method", "CHEQUE"));
        assertThat(over.at("/payment/allocated").asLong()).isEqualTo(268_200);
        assertThat(over.at("/payment/amount").asLong() - over.at("/payment/allocated").asLong()).as("trop-perçu").isEqualTo(11_800);
        assertThat(ok(get("/api/v1/factures/" + id), null).at("/summary/state").asText()).isEqualTo("PAYEE");
        assertThat(ok(get("/api/v1/tableau"), null).get("dso").asInt()).as("émise le 08/11, soldée le 24/12").isEqualTo(46);
        assertThat(problem(post("/api/v1/paiements"), Map.of("crecheSlug", "lagon-bleu", "receivedOn", "2027-01-01", "amount", 10,
                "method", "VIREMENT"), 422).get("code").asText()).as("pas dans le futur").isEqualTo("date");

        ok(delete("/api/v1/paiements/" + over.at("/payment/id").asText()), null);
        assertThat(ok(get("/api/v1/factures/" + id), null).at("/summary/state").asText()).isEqualTo("RETARD");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'PAIEMENT_SUPPRIME'", Integer.class)).isEqualTo(1);
    }

    @Test
    void avoirsPartielEtTotal() throws Exception {
        var invoice = subscribedAndInvoiced();
        var id = invoice.at("/summary/id").asText();
        var tablets = invoice.at("/lines/1");
        assertThat(tablets.get("label").asText()).isEqualTo("Tablette supplémentaire (2)");
        var lineId = tablets.get("id").asText();

        session.removeAttribute("console.confirmedAt");
        var credit = Map.of("lines", List.of(Map.of("lineId", lineId, "amount", 24_000)), "reason", "Tablettes jamais livrées");
        problem(post("/api/v1/factures/" + id + "/avoir"), credit, 403);
        confirmed();
        var avoir = ok(post("/api/v1/factures/" + id + "/avoir"), credit);
        assertThat(avoir.at("/summary/number").asText()).isEqualTo("AV2026-0001");
        assertThat(avoir.at("/summary/totalTtc").asLong()).isEqualTo(26_640);
        assertThat(avoir.at("/summary/correctsNumber").asText()).isEqualTo("F2026-0001");
        assertThat(avoir.at("/lines/0/tgcRate").asInt()).isEqualTo(110);

        var after = ok(get("/api/v1/factures/" + id), null);
        assertThat(after.at("/summary/credited").asLong()).isEqualTo(26_640);
        assertThat(after.at("/summary/remaining").asLong()).isEqualTo(246_420);
        assertThat(after.at("/creditable/1/creditable").asLong()).isZero();
        assertThat(problem(post("/api/v1/factures/" + id + "/avoir"), Map.of("lines", List.of(Map.of("lineId", lineId, "amount", 1)),
                "reason", "Encore"), 422).get("detail").asText()).contains("0 F au plus");

        var formula = after.at("/creditable/0");
        ok(post("/api/v1/factures/" + id + "/avoir"), Map.of("lines", List.of(Map.of("lineId", formula.get("lineId").asText(),
                "amount", formula.get("creditable").asLong())), "reason", "Résiliation"));
        var cancelled = ok(get("/api/v1/factures/" + id), null);
        assertThat(cancelled.at("/summary/state").asText()).isEqualTo("ANNULEE");
        assertThat(cancelled.at("/credits").size()).isEqualTo(2);
        assertThat(ok(get("/api/v1/envoi"), null).get("pending").asInt()).as("facture et deux avoirs").isEqualTo(3);
    }

    @Test
    void avenantAuProrataEtRenouvellementPrepare() throws Exception {
        subscribedAndInvoiced();
        clock.on("2027-05-01");
        var withSupport = new HashMap<>(contract(12, 2, true, "2026-11-01"));
        withSupport.put("changeOn", "2027-05-01");
        var saved = ok(put("/api/v1/parc/lagon-bleu/contrat"), withSupport);
        // Support Premium ajouté le 01/05/2027 : 4 000 F × 12 × 184 / 365 = 24 197 F.
        var complement = ok(get("/api/v1/factures/" + saved.get("complementInvoiceId").asText()), null);
        assertThat(complement.at("/summary/kind").asText()).isEqualTo("COMPLEMENT");
        assertThat(complement.at("/lines/0/label").asText()).isEqualTo("Complément : Support Premium");
        assertThat(complement.at("/summary/totalHt").asLong()).isEqualTo(24_197);
        assertThat(saved.at("/contract/history").size()).isEqualTo(2);
        assertThat(saved.at("/contract/current/startsOn").asText()).isEqualTo("2027-05-01");

        clock.on("2027-06-01");
        var fewer = new HashMap<>(contract(12, 0, false, "2026-11-01"));
        fewer.put("changeOn", "2027-06-01");
        var reduced = ok(put("/api/v1/parc/lagon-bleu/contrat"), fewer);
        assertThat(reduced.get("complementInvoiceId").isNull()).isTrue();
        assertThat(reduced.get("toCredit").asLong()).as("tablettes et support retirés : à rendre par avoir").isPositive();
        assertThat(reduced.get("toCreditInvoiceNumber").asText()).isEqualTo("F2026-0001");

        // 45 jours avant la période suivante (01/11/2027) : le brouillon de renouvellement se prépare seul, une fois.
        clock.on("2027-09-16");
        assertThat(contracts.prepareRenewals()).isZero();
        clock.on("2027-09-17");
        assertThat(contracts.prepareRenewals()).isEqualTo(1);
        assertThat(contracts.prepareRenewals()).isZero();
        var renewal = jdbc.queryForMap("SELECT subject, total_ht, status FROM invoice WHERE period_from = DATE '2027-11-01'");
        assertThat(renewal.get("subject")).isEqualTo("Abonnement 11/2027 – 10/2028");
        assertThat(renewal.get("total_ht")).as("Ti'Cagou Micro seul").isEqualTo(222_000L);
        assertThat(renewal.get("status")).isEqualTo("BROUILLON");
    }

    @Test
    void boiteDEnvoiModifierEnvoyerEchecEtEcarter() throws Exception {
        var invoice = subscribedAndInvoiced();
        var message = ok(get("/api/v1/envoi"), null).at("/messages/0");
        var id = message.get("id").asText();
        var edited = ok(put("/api/v1/envoi/" + id), Map.of("to", "direction@lagon-bleu.nc; compta@lagon-bleu.nc", "cc", "",
                "subject", "Facture F2026-0001 — abonnement", "body", "Bonjour,\n\nVoici la facture.\n"));
        assertThat(edited.get("subject").asText()).isEqualTo("Facture F2026-0001 — abonnement");

        mail.down = true;
        var failed = ok(post("/api/v1/envoi/envoyer"), Map.of("ids", List.of(id)));
        assertThat(failed.at("/0/sent").asBoolean()).isFalse();
        assertThat(failed.at("/0/error").asText()).contains("refusé");
        assertThat(ok(get("/api/v1/envoi"), null).at("/messages/0/status").asText()).isEqualTo("PREPARE");

        mail.down = false;
        var sent = ok(post("/api/v1/envoi/envoyer"), Map.of("ids", List.of(id)));
        assertThat(sent.at("/0/sent").asBoolean()).isTrue();
        assertThat(mail.sent).hasSize(1);
        var mime = mail.sent.getFirst();
        assertThat(mime.getAllRecipients()).hasSize(2);
        assertThat(mime.getSubject()).isEqualTo("Facture F2026-0001 — abonnement");
        var parts = (Multipart) mime.getContent();
        assertThat(parts.getBodyPart(1).getFileName()).isEqualTo("F2026-0001.pdf");
        var after = ok(get("/api/v1/factures/" + invoice.at("/summary/id").asText()), null);
        assertThat(after.at("/summary/state").asText()).isEqualTo("ENVOYEE");
        assertThat(after.at("/messages/0/status").asText()).isEqualTo("ENVOYE");
        assertThat(ok(post("/api/v1/envoi/envoyer"), Map.of("ids", List.of(id))).at("/0/sent").asBoolean()).as("pas deux fois").isFalse();
        assertThat(mail.sent).hasSize(1);

        // Avoir : son message est écarté (remis en main propre).
        confirmed();
        ok(post("/api/v1/factures/" + invoice.at("/summary/id").asText() + "/avoir"), Map.of("lines",
                List.of(Map.of("lineId", invoice.at("/lines/1/id").asText(), "amount", 1_000)), "reason", "Geste commercial"));
        var pending = ok(get("/api/v1/envoi").param("statut", "PREPARE"), null).at("/messages/0");
        assertThat(pending.get("subject").asText()).isEqualTo("Avoir AV2026-0001 sur la facture F2026-0001");
        ok(post("/api/v1/envoi/ecarter"), Map.of("ids", List.of(pending.get("id").asText()), "reason", "Remis en main propre"));
        assertThat(ok(get("/api/v1/envoi"), null).get("pending").asInt()).isZero();
        assertThat(problem(put("/api/v1/envoi/" + pending.get("id").asText()), Map.of("subject", "x", "body", "y"), 422).get("code").asText())
                .isEqualTo("message_traite");
    }

    @Test
    void parametresNouvelleVersionDuBaremeEtNouveauTaux() throws Exception {
        var settings = ok(get("/api/v1/bareme"), null);
        // Barème de la formule unique (V9) : 15 000 F quelle que soit la taille, support compris, depuis le 01/11/2026.
        assertThat(settings.at("/versions/0/list/label").asText()).isEqualTo("Barème formule unique");
        assertThat(settings.at("/versions/0/list/validFrom").asText()).isEqualTo("2026-11-01");
        assertThat(settings.at("/versions/0/items").size()).as("formule × 3 tailles, PC, tablette, 5 prestations").isEqualTo(10);
        assertThat(settings.at("/versions/1/list/validTo").asText()).isEqualTo("2026-10-31");
        for (var places : List.of(12, 30, 60)) {
            var signedNow = new HashMap<>(contract(places, 0, false, "2026-12-01"));
            signedNow.put("signedOn", "2026-11-08");
            assertThat(ok(post("/api/v1/parc/lagon-bleu/contrat/devis"), signedNow).at("/0/total").asLong()).as(places + " places").isEqualTo(15_000);
        }
        var withSupport = new HashMap<>(contract(12, 0, true, "2026-12-01"));
        withSupport.put("signedOn", "2026-11-08");
        assertThat(problem(post("/api/v1/parc/lagon-bleu/contrat/devis"), withSupport, 422).get("detail").asText())
                .as("support compris : plus de Support Premium au barème").contains("SUPPORT");
        assertThat(settings.get("missingMentions").size()).isEqualTo(4);

        var items = new ArrayList<Map<String, Object>>();
        settings.at("/versions/0/items").forEach(i -> {
            var item = new HashMap<String, Object>();
            i.fieldNames().forEachRemaining(f -> item.put(f, i.get(f).isNull() ? null : i.get(f).isNumber() ? i.get(f).asLong() : i.get(f).asText()));
            if ("COMPLET".equals(item.get("code")) && "MICRO".equals(item.get("tier"))) {
                item.put("price", 19_000L);
            }
            items.add(item);
        });
        var version = new HashMap<String, Object>(Map.of("label", "Barème 2027", "validFrom", "2027-01-01", "microMax", 15, "structureMax", 40,
                "grandeMax", 80, "includedDesktops", 1, "includedTablets", 2, "engagement3Discount", 100, "extraStructureDiscount", 150));
        version.put("items", items);
        var updated = ok(post("/api/v1/bareme"), version);
        assertThat(updated.at("/versions/0/list/label").asText()).isEqualTo("Barème 2027");
        assertThat(updated.at("/versions/1/list/validTo").asText()).isEqualTo("2026-12-31");
        assertThat(problem(post("/api/v1/bareme"), version, 422).get("code").asText()).isEqualTo("bareme_date");

        // Contrat signé en 2027 : au nouveau barème ; celui signé en 2026 garde l'ancien prix.
        var signed2027 = new HashMap<>(contract(12, 0, false, "2027-02-01"));
        signed2027.put("signedOn", "2027-01-10");
        assertThat(ok(post("/api/v1/parc/lagon-bleu/contrat/devis"), signed2027).at("/0/total").asLong()).isEqualTo(19_000);
        assertThat(ok(post("/api/v1/parc/lagon-bleu/contrat/devis"), contract(12, 0, false, "2026-11-01")).at("/0/total").asLong())
                .isEqualTo(18_500);

        var rates = ok(post("/api/v1/tgc"), Map.of("nature", "PRESTATION", "rate", 130, "validFrom", "2027-01-01", "note", "Hausse"));
        assertThat(rates.get("tgcRates").size()).isEqualTo(4);
        mentions();
        clock.on("2027-01-05");
        var draft = invoices.createDraft(new InvoiceService.DraftInput("lagon-bleu", "Formation", null, null, null,
                List.of(new InvoiceService.LineInput("Formation équipe", null, 2, 15_000, "PRESTATION"))));
        assertThat(draft.summary().totalTgc()).as("13 % de 30 000 F").isEqualTo(3_900);
        assertThat(jdbc.queryForList("SELECT action FROM audit_event", String.class)).contains("BAREME_VERSION", "TGC_TAUX");
    }

    // --- Reporting (phase 6)

    JsonNode report(String from, String to) throws Exception {
        return ok(get("/api/v1/reporting").param("du", from).param("au", to), null);
    }

    @Test
    void reportingChiffreDAffairesTgcBalanceAgeeRentabiliteEtExports() throws Exception {
        var invoice = subscribedAndInvoiced();
        var id = invoice.at("/summary/id").asText();
        var tablets = invoice.at("/lines/1/id").asText();
        ok(post("/api/v1/factures/" + id + "/avoir"), Map.of("lines", List.of(Map.of("lineId", tablets, "amount", 24_000)), "reason", "Tablettes"));
        ok(post("/api/v1/parc/lagon-bleu/notes"), Map.of("kind", "APPEL", "text", "Formation à la facturation", "minutes", 90));
        clock.on("2026-11-20");
        ok(post("/api/v1/paiements"), Map.of("crecheSlug", "lagon-bleu", "receivedOn", "2026-11-20", "amount", 4_860, "method", "VIREMENT"));

        var report = report("2026-01-01", "2026-12-31");
        long tgcOfInvoices = jdbc.queryForObject(
                "SELECT sum(CASE WHEN type = 'AVOIR' THEN -total_tgc ELSE total_tgc END) FROM invoice WHERE status = 'VALIDEE'", Long.class);
        assertThat(report.at("/kpis/tgc").asLong()).as("TGC égale aux factures moins avoirs").isEqualTo(tgcOfInvoices).isEqualTo(27_060 - 2_640);
        long byRate = 0;
        for (var rate : report.get("tgc")) {
            byRate += rate.get("tgc").asLong();
        }
        assertThat(byRate).isEqualTo(tgcOfInvoices);
        assertThat(report.at("/tgc/0/rate").asInt()).isEqualTo(110);
        assertThat(report.at("/tgc/0/base").asLong()).isEqualTo(246_000 - 24_000);
        assertThat(report.at("/kpis/revenueHt").asLong()).isEqualTo(222_000);
        assertThat(report.at("/kpis/subscriptionsHt").asLong()).isEqualTo(222_000);
        assertThat(report.at("/kpis/collected").asLong()).isEqualTo(4_860);
        assertThat(report.at("/kpis/mrr").asLong()).as("abonnement annuel ramené au mois").isEqualTo(20_500);
        assertThat(report.get("months").size()).isEqualTo(12);
        assertThat(report.at("/months/10/month").asText()).isEqualTo("2026-11");
        assertThat(report.at("/months/10/ht").asLong()).isEqualTo(222_000);
        assertThat(report.at("/months/10/collected").asLong()).isEqualTo(4_860);
        assertThat(report.get("mrrByPlan").toString()).contains("\"key\":\"FORMULE\"", "\"key\":\"OPTIONS\"").doesNotContain("MODULES");
        long planTotal = 0;
        for (var p : report.get("mrrByPlan")) {
            planTotal += p.get("monthly").asLong();
        }
        assertThat(planTotal).isEqualTo(20_500);
        assertThat(report.at("/kpis/departures").asInt()).isZero();
        assertThat(report.at("/kpis/churnTenths").asInt()).isZero();
        assertThat(ok(get("/api/v1/tableau"), null).get("mrrPrevious").asLong()).as("contrat commencé en novembre").isZero();

        // Rentabilité : 90 min sur 12 mois à 9 000 F de l'heure, plus 1 200 F d'hébergement.
        assertThat(report.at("/profitMonths").asInt()).isEqualTo(12);
        assertThat(report.at("/profitability/0/revenue").asLong()).isEqualTo(20_500);
        assertThat(report.at("/profitability/0/cost").asLong()).isEqualTo(1_125 + 1_200);
        assertThat(report.at("/profitability/0/margin").asLong()).isEqualTo(20_500 - 2_325);
        ok(put("/api/v1/reporting/reglages"), Map.of("supportHourlyRate", 12_000, "hostingPerCreche", 1_500));
        assertThat(report("2026-01-01", "2026-12-31").at("/profitability/0/cost").asLong()).isEqualTo(1_500 + 1_500);

        // Balance âgée aux bornes : échéance le 08/12/2026, reste 273 060 − 26 640 − 4 860.
        long remaining = 241_560;
        assertThat(report.at("/aging/0/notDue").asLong()).isEqualTo(remaining);
        var bounds = new String[][] {{"2026-12-08", "notDue"}, {"2026-12-09", "d30"}, {"2027-01-07", "d30"}, {"2027-01-08", "d60"},
                {"2027-02-06", "d60"}, {"2027-02-07", "d90"}, {"2027-03-08", "d90"}, {"2027-03-09", "over"}};
        for (var c : bounds) {
            clock.on(c[0]);
            var aging = report("2026-01-01", "2026-12-31").at("/aging/0");
            assertThat(aging.get(c[1]).asLong()).as(c[0]).isEqualTo(remaining);
            assertThat(aging.get("total").asLong()).isEqualTo(remaining);
        }
        assertThat(report("2026-01-01", "2026-12-31").at("/agingTotal/over").asLong()).isEqualTo(remaining);
        assertThat(report("2026-01-01", "2026-12-31").get("risks").toString()).as("un seul retard").doesNotContain("PAIEMENT");

        // Filtres : une ancienne formule ne voit rien de cette crèche, à la formule unique.
        var other = ok(get("/api/v1/reporting").param("du", "2026-01-01").param("au", "2026-12-31").param("formule", "GESTION"), null);
        assertThat(other.at("/kpis/revenueHt").asLong()).isZero();
        assertThat(other.get("aging").size()).isZero();
        problem(get("/api/v1/reporting").param("du", "2026-12-31").param("au", "2026-01-01"), null, 422);

        // Exports pour le comptable.
        var csv = call(get("/api/v1/reporting/journal-ventes.csv").param("du", "2026-01-01").param("au", "2026-12-31"), null).getResponse();
        var text = csv.getContentAsString(StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿Date;Pièce;Type;Client;RIDET;Crèche;Formule;Objet;Échéance;Total HT;TGC;Total TTC;Base HT 11 %;TGC 11 %\r\n");
        assertThat(text).contains("08/11/2026;F2026-0001;Facture;", ";Ti’Cagou;", ";08/12/2026;246000;27060;273060;246000;27060\r\n");
        assertThat(text).contains(";AV2026-0001;Avoir;", ";-24000;-2640;-26640;-24000;-2640\r\n");
        assertThat(csv.getHeader("Content-Disposition")).contains("journal-ventes-2026-01-01-2026-12-31.csv");
        var receipts = call(get("/api/v1/reporting/encaissements.csv").param("du", "2026-01-01").param("au", "2026-12-31"), null)
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(receipts).contains("20/11/2026;SARL Lagon Bleu;4860;Virement;;F2026-0001\r\n");
        var excel = call(get("/api/v1/reporting/reporting.xlsx").param("du", "2026-01-01").param("au", "2026-12-31"), null).getResponse();
        assertThat(excel.getStatus()).isEqualTo(200);
        assertThat(new String(excel.getContentAsByteArray(), 0, 2, StandardCharsets.ISO_8859_1)).as("classeur zip").isEqualTo("PK");
        assertThat(jdbc.queryForList("SELECT target FROM audit_event WHERE action = 'EXPORT_COMPTABLE'", String.class))
                .containsExactly("journal-ventes", "encaissements", "reporting-excel");
    }
}
