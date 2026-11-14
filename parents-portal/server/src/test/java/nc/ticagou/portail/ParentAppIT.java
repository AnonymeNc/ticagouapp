package nc.ticagou.portail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Application Ti’Cagou Parents : jeton porteur, CORS, suppression du compte (ADR 0024, maquette Pa8). */
class ParentAppIT extends PortalTest {

    private static final String MARIE = "marie.wamytan@example.nc";
    private static final String APP = "parents/1.0.0 (ios)";

    private MvcResult send(MockHttpServletRequestBuilder request, String bearer, Object body) throws Exception {
        request.header("X-Ticagou-Portail", "1").header("X-Ticagou-App", APP);
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        }
        return mvc.perform(request).andReturn();
    }

    private String code() throws Exception {
        MAILS.clear();
        send(post("/api/v1/auth/request"), null, Map.of("email", MARIE));
        return code(awaitMail());
    }

    private String appLogin() throws Exception {
        var verified = send(post("/api/v1/auth/verify"), null, Map.of("email", MARIE, "code", code()));
        assertThat(status(verified)).isEqualTo(200);
        assertThat(verified.getResponse().getHeader("Set-Cookie")).as("pas de cookie pour l’application").isNull();
        return body(verified).get("token").asText();
    }

    @Test
    void lApplicationSeConnecteParCodeEtJetonPorteur() throws Exception {
        var creche = activate("Les P'tits Cagous");
        batch(creche, fixture("batch.json"));
        var token = appLogin();
        assertThat(token).isNotBlank();
        assertThat(body(send(get("/api/v1/me"), token, null)).toString()).contains(MARIE);
        assertThat(status(send(get("/api/v1/me"), "faux-jeton", null))).isEqualTo(401);

        // Requête préalable de la coque Capacitor : admise, sans cookie.
        var preflight = mvc.perform(options("/api/v1/me").header("Origin", "capacitor://localhost")
                .header("Access-Control-Request-Method", "GET").header("Access-Control-Request-Headers", "authorization")).andReturn();
        assertThat(status(preflight)).isEqualTo(200);
        assertThat(preflight.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo("capacitor://localhost");
        assertThat(preflight.getResponse().getHeader("Access-Control-Allow-Credentials")).isNull();

        assertThat(status(send(post("/api/v1/auth/logout"), token, null))).isEqualTo(204);
        assertThat(status(send(get("/api/v1/me"), token, null))).isEqualTo(401);
    }

    @Test
    void leJetonDeMediaNOuvreQueLesFichiers() throws Exception {
        var creche = activate("Les P'tits Cagous");
        batch(creche, fixture("batch.json"));
        var token = appLogin();
        var media = body(send(get("/api/v1/me/media-token"), token, null)).get("token").asText();
        var photo = "/api/v1/me/children/" + creche.id() + "/0b9a3c1e-0000-4000-8000-000000001000/photo";

        // Sans session ni jeton : refusé ; avec le jeton de média : la photo (ou son absence), jamais 401.
        assertThat(status(mvc.perform(get(photo)).andReturn())).isEqualTo(401);
        assertThat(status(mvc.perform(get(photo + "?jeton=" + media)).andReturn())).isNotEqualTo(401);
        assertThat(status(mvc.perform(get(photo + "?jeton=" + media + "x")).andReturn())).isEqualTo(401);
        // Il ne vaut pas session.
        assertThat(status(mvc.perform(get("/api/v1/me?jeton=" + media)).andReturn())).isEqualTo(401);
    }

    @Test
    void supprimerSonCompteEffaceLAccesEtPrevientLaCreche() throws Exception {
        var creche = activate("Les P'tits Cagous");
        batch(creche, fixture("batch.json"));
        var token = appLogin();
        send(post("/api/v1/me/push/native"), token, Map.of("platform", "IOS", "token", "iphone-de-marie", "categories", List.of("PHOTOS")));

        // Mauvais code : rien n’est effacé.
        assertThat(status(send(post("/api/v1/me/account/delete"), token, Map.of("code", "000000")))).isEqualTo(401);
        assertThat(status(send(get("/api/v1/me"), token, null))).isEqualTo(200);

        assertThat(status(send(post("/api/v1/me/account/delete"), token, Map.of("code", code())))).isEqualTo(204);
        assertThat(status(send(get("/api/v1/me"), token, null))).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM native_device", Long.class)).isZero();

        // La crèche reçoit un message de la famille à sa prochaine synchronisation.
        var inbox = body(signed(creche, "GET", "/sync/v1/inbox?after=0", new byte[0], java.time.Instant.now())).get("items");
        assertThat(inbox).anySatisfy(i -> {
            assertThat(i.get("type").asText()).isEqualTo("MESSAGE");
            assertThat(i.at("/payload/body").asText()).contains("supprimé son compte du portail");
        });

        // Plus de code de connexion pour cette adresse (même réponse, aucun e-mail)…
        MAILS.clear();
        send(post("/api/v1/auth/request"), null, Map.of("email", MARIE));
        Thread.sleep(300);
        assertThat(MAILS).isEmpty();
        // … jusqu’à ce que la crèche renvoie sa fiche (réinvitation).
        batch(creche, Map.of("items", List.of(Map.of("seq", 40, "type", "GUARDIAN", "id", "0b9a3c1e-0000-4000-8000-000000000010",
                "deleted", false, "payload", Map.of("familyId", "0b9a3c1e-0000-4000-8000-000000000100", "firstName", "Marie",
                        "lastName", "Wamytan", "email", MARIE)))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_deletion", Long.class)).isZero();
    }
}
