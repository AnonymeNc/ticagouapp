package nc.ticagou.console.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
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

import nc.ticagou.console.auth.CurrentOperator;
import nc.ticagou.console.billing.BillingIT;
import nc.ticagou.console.billing.InvoiceService;
import nc.ticagou.console.parc.ParcRepository;

/**
 * Ordres aux instances : donnés avec le code, proposés après un paiement, pris une seule fois par l'hôte, compte rendu ;
 * création d'une crèche et démonstrations depuis la console.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(BillingIT.Config.class)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"console.scheduling=false", "console.secret-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "console.mail-from=Ti'Cagou <facturation@ticagou.nc>", "console.host-token=jeton-hote"})
class OrderIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired BillingIT.MovableClock clock;
    @Autowired ParcRepository parc;
    @Autowired InvoiceService invoices;

    MockHttpSession session;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE instance_order, reminder, outbox_message, payment_allocation, payment, invoice_line, invoice, subscription_contract, "
                + "counter, creche_note, creche_snapshot, alert_history, creche, client, audit_event CASCADE");
        jdbc.update("""
                UPDATE issuer SET name = 'Ti''Cagou', ridet = '1234567.001', address = 'Nouméa', bank = 'BCI', iban = 'NC00 1', copy_email = NULL""");
        jdbc.update("UPDATE collector SET last_success_at = NULL, data = NULL");
        clock.now = BillingIT.START;
        session = new MockHttpSession();
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

    /** Requête de l'hôte (creche.sh appliquer), avec son jeton. */
    MvcResult host(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer jeton-hote")).andReturn();
    }

    void confirmed() {
        session.setAttribute("console.confirmedAt", clock.instant());
    }

    @Test
    void creerUneCrecheDepuisLaConsoleEtUneSeuleExecution() throws Exception {
        var order = Map.of("type", "CREER", "slug", "tiare", "params", Map.of("nom", "Crèche Tiare", "domaine", "creche-tiare.nc"));
        assertThat(problem(post("/api/v1/ordres"), order, 403).get("code").asText()).isEqualTo("confirmation_required");
        confirmed();
        var given = ok(post("/api/v1/ordres"), order);
        assertThat(given.get("status").asText()).isEqualTo("EN_ATTENTE");
        assertThat(given.get("params").toString()).as("« nom » ne part pas à l'hôte").doesNotContain("nom");
        assertThat(jdbc.queryForObject("SELECT name || '/' || lifecycle FROM creche", String.class)).isEqualTo("Crèche Tiare/PROSPECT");
        assertThat(problem(post("/api/v1/ordres"), order, 409).get("code").asText()).as("pas de doublon").isEqualTo("ordre_ouvert");

        assertThat(mvc.perform(get("/interne/v1/ordres")).andReturn().getResponse().getStatus()).as("jeton exigé").isEqualTo(401);
        var lines = host(get("/interne/v1/ordres")).getResponse().getContentAsString(StandardCharsets.UTF_8);
        var id = given.get("id").asText();
        assertThat(lines).isEqualTo(id + "\tCREER\ttiare\tdomaine=creche-tiare.nc\n");
        assertThat(host(post("/interne/v1/ordres/" + id + "/debut")).getResponse().getStatus()).isEqualTo(204);
        assertThat(host(post("/interne/v1/ordres/" + id + "/debut")).getResponse().getStatus()).as("pris une seule fois").isEqualTo(409);
        assertThat(host(get("/interne/v1/ordres")).getResponse().getContentAsString(StandardCharsets.UTF_8)).isEmpty();

        var output = "Crèche créée. Lien de démarrage à remettre à la directrice (une seule utilisation) :\n"
                + "  https://creche-tiare.nc/setup?jeton=0a1b2c3d\n";
        assertThat(host(post("/interne/v1/ordres/" + id + "/fin").param("ok", "true").contentType(MediaType.TEXT_PLAIN).content(output))
                .getResponse().getStatus()).isEqualTo(204);
        assertThat(host(post("/interne/v1/ordres/" + id + "/fin").param("ok", "true").content("x")).getResponse().getStatus()).isEqualTo(409);
        var board = ok(get("/api/v1/ordres"), null);
        assertThat(board.at("/orders/0/status").asText()).isEqualTo("FAIT");
        assertThat(board.at("/orders/0/result/setupLink").asText()).isEqualTo("https://creche-tiare.nc/setup?jeton=0a1b2c3d");
        assertThat(board.at("/orders/0/crecheName").asText()).isEqualTo("Crèche Tiare");
        assertThat(jdbc.queryForObject("SELECT lifecycle FROM creche", String.class)).isEqualTo("CLIENT");
        assertThat(jdbc.queryForList("SELECT action FROM audit_event", String.class)).contains("ORDRE_DONNE", "ORDRE_FAIT");
    }

    @Test
    void demonstrationPuisSuppressionEtRefusPourUneVraieCreche() throws Exception {
        confirmed();
        var demo = ok(post("/api/v1/ordres"), Map.of("type", "DEMO", "params", Map.of("formule", "Gestion")));
        var slug = demo.get("slug").asText();
        assertThat(slug).isEqualTo("demo-20261108-0900");
        assertThat(jdbc.queryForObject("SELECT lifecycle FROM creche WHERE slug = ?", String.class, slug)).isEqualTo("DEMO");
        var id = demo.get("id").asText();
        host(post("/interne/v1/ordres/" + id + "/debut"));
        host(post("/interne/v1/ordres/" + id + "/fin").param("ok", "true").content(
                "  https://demo-20261108-0900.ticagou.nc/setup?jeton=abc\nDémonstration prête : https://demo-20261108-0900.ticagou.nc\n"));
        var done = ok(get("/api/v1/parc/" + slug + "/ordres"), null);
        assertThat(done.at("/0/result/url").asText()).isEqualTo("https://demo-20261108-0900.ticagou.nc");
        assertThat(done.at("/0/result/setupLink").isMissingNode()).isTrue();

        parc.ensure("lagon-bleu", "Lagon Bleu", "CLIENT", clock.instant());
        assertThat(problem(post("/api/v1/ordres"), Map.of("type", "SUPPRIMER_DEMO", "slug", "lagon-bleu"), 422).get("code").asText())
                .isEqualTo("pas_une_demo");
        var removal = ok(post("/api/v1/ordres"), Map.of("type", "SUPPRIMER_DEMO", "slug", slug));
        host(post("/interne/v1/ordres/" + removal.get("id").asText() + "/debut"));
        host(post("/interne/v1/ordres/" + removal.get("id").asText() + "/fin").param("ok", "true").content("supprimée"));
        assertThat(jdbc.queryForList("SELECT slug FROM creche", String.class)).containsExactly("lagon-bleu");
    }

    @Test
    void paiementDeLAbonnementProposeLOrdreAConfirmer() throws Exception {
        parc.ensure("lagon-bleu", "Lagon Bleu", "PROSPECT", clock.instant());
        var contract = new HashMap<String, Object>(Map.of("plan", "GESTION", "places", 12, "modules", List.of("REPAS"), "extraDesktops", 0,
                "extraTablets", 1, "premiumSupport", false, "engagementYears", 1, "rhythm", "ANNUEL", "extraStructure", false, "freeDiscount", 0));
        contract.put("startsOn", "2026-11-01");
        contract.put("signedOn", "2026-10-15");
        ok(put("/api/v1/parc/lagon-bleu/contrat"), contract);
        // L'instance existe et porte encore l'ancien abonnement.
        jdbc.update("""
                UPDATE creche SET host = '{"container":"healthy","subscription":{"plan":"Gestion — 12 places","places":12,"desktops":1,
                    "tablets":2,"validUntil":"2026-10-31","modules":"INSCRIPTIONS,FACTURATION,PORTAIL_PARENTS,REPAS"}}'::jsonb""");
        var draft = ok(post("/api/v1/parc/lagon-bleu/contrat/prochaine-facture"), null);
        confirmed();
        var invoice = ok(post("/api/v1/factures/" + draft.at("/summary/id").asText() + "/valider"), null);

        var board = ok(get("/api/v1/ordres"), null);
        assertThat(board.get("misaligned").size()).as("rien de payé : pas encore d'écart à signaler").isZero();
        ok(post("/api/v1/paiements"), Map.of("crecheSlug", "lagon-bleu", "receivedOn", "2026-11-08", "amount", invoice.at("/summary/totalTtc").asLong(),
                "method", "VIREMENT"));
        board = ok(get("/api/v1/ordres"), null);
        var proposed = board.at("/orders/0");
        assertThat(proposed.get("status").asText()).isEqualTo("A_CONFIRMER");
        assertThat(proposed.get("reason").asText()).isEqualTo("Après paiement de F2026-0001");
        assertThat(proposed.at("/params/jusqu_au").asText()).isEqualTo("2027-10-31");
        assertThat(proposed.at("/params/tablettes").asText()).as("2 comprises + 1").isEqualTo("3");
        assertThat(proposed.at("/params/modules").asText()).isEqualTo("REPAS");
        assertThat(board.at("/misaligned/0/differences").toString()).contains("Tablettes : 2 dans l’instance, 3 au contrat");
        assertThat(host(get("/interne/v1/ordres")).getResponse().getContentAsString(StandardCharsets.UTF_8)).as("pas avant confirmation").isEmpty();

        session.removeAttribute("console.confirmedAt");
        problem(post("/api/v1/ordres/" + proposed.get("id").asText() + "/confirmer"), null, 403);
        confirmed();
        assertThat(ok(post("/api/v1/ordres/" + proposed.get("id").asText() + "/confirmer"), null).get("status").asText()).isEqualTo("EN_ATTENTE");
        assertThat(host(get("/interne/v1/ordres")).getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\tABONNEMENT\tlagon-bleu\tnumero=C-", "offre=gestion;places=12;pc=1;tablettes=3;jusqu_au=2027-10-31;modules=REPAS");
        assertThat(ok(get("/api/v1/parc/lagon-bleu/ordres/abonnement"), null).get("jusqu_au").asText()).isEqualTo("2027-10-31");

        ok(post("/api/v1/ordres/" + proposed.get("id").asText() + "/annuler"), null);
        assertThat(ok(get("/api/v1/ordres"), null).at("/orders/0/status").asText()).isEqualTo("ANNULE");
    }

    @Test
    void ordresRefusesOuInconnus() throws Exception {
        confirmed();
        parc.ensure("niaoulis", "Les Niaoulis", "CLIENT", clock.instant());
        assertThat(problem(post("/api/v1/ordres"), Map.of("type", "SUPPRIMER", "slug", "niaoulis"), 422).get("detail").asText())
                .contains("Ordre inconnu");
        assertThat(problem(post("/api/v1/ordres"), Map.of("type", "METTRE_A_JOUR", "slug", "niaoulis", "params", Map.of("version", "1.5; reboot")), 422)
                .get("detail").asText()).contains("Version");
        assertThat(problem(post("/api/v1/ordres"), Map.of("type", "SUSPENDRE", "slug", "inconnue"), 404).get("code").asText()).isEqualTo("creche_inconnue");
        var suspend = ok(post("/api/v1/ordres"), Map.of("type", "SUSPENDRE", "slug", "niaoulis", "reason", "Impayé après relance 3"));
        host(post("/interne/v1/ordres/" + suspend.get("id").asText() + "/debut"));
        assertThat(problem(post("/api/v1/ordres/" + suspend.get("id").asText() + "/annuler"), null, 422).get("code").asText())
                .as("l'hôte l'exécute").isEqualTo("ordre_en_cours");
        clock.now = clock.now.plusSeconds(31 * 60);
        assertThat(ok(get("/api/v1/ordres"), null).at("/orders/0/stale").asBoolean()).isTrue();
        assertThat(ok(post("/api/v1/ordres/" + suspend.get("id").asText() + "/annuler"), null).get("status").asText()).isEqualTo("ANNULE");
        host(post("/interne/v1/ordres/" + suspend.get("id").asText() + "/fin").param("ok", "true").content("tard"));
        assertThat(ok(get("/api/v1/ordres"), null).at("/orders/0/status").asText()).as("compte rendu tardif ignoré").isEqualTo("ANNULE");
    }
}
