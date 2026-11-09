package nc.ticagou.portail;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nc.ticagou.portail.auth.RateLimiter;
import nc.ticagou.portail.sync.SyncService;

/** Base des tests du portail : application complète, PostgreSQL 17, base vidée, e-mails capturés. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PortalTest.Config.class)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"portal.scheduling=false", "portal.secure-cookie=true", "portal.admin-token=jeton-hebergeur",
        "portal.public-url=https://parents.ticagou.nc", "portal.supervision-email=support@ticagou.nc"})
public abstract class PortalTest {

    protected static final Path CONTRACT = Path.of("..", "contract");

    /** E-mails envoyés pendant le test. */
    protected static final List<SimpleMailMessage> MAILS = new CopyOnWriteArrayList<>();

    /** Notifications envoyées aux services de notification pendant le test (ADR 0018). */
    protected record SentPush(String endpoint, Map<String, String> headers, byte[] body) {
    }

    protected static final List<SentPush> PUSHES = new CopyOnWriteArrayList<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:17-alpine");
        }

        /** Service de notification simulé : un abonnement dont l'adresse contient « expire » répond 410. */
        @Bean
        @Primary
        nc.ticagou.portail.push.PushTransport capturingPushTransport() {
            return new nc.ticagou.portail.push.PushTransport() {
                @Override
                public int post(String endpoint, Map<String, String> headers, byte[] body) {
                    PUSHES.add(new SentPush(endpoint, headers, body));
                    return endpoint.contains("expire") ? 410 : 201;
                }
            };
        }

        @Bean
        @Primary
        JavaMailSender capturingMailSender() {
            return new JavaMailSenderImpl() {
                @Override
                public void send(SimpleMailMessage... messages) {
                    MAILS.addAll(List.of(messages));
                }
            };
        }
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected SyncService sync;

    @Autowired
    private RateLimiter limiter;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE portal_structure, activation_code, guardian, child, item, item_family, item_child, inbox, "
                + "login_request, parent_session, supervision_code, supervision_site, supervision_alert, push_subscription CASCADE");
        limiter.reset();
        MAILS.clear();
        PUSHES.clear();
    }

    // --- Structure de crèche ------------------------------------------------------------------

    /** Serveur de crèche simulé : sa paire de clés et l'identifiant attribué par le portail. */
    protected record Creche(UUID id, KeyPair keys) {
    }

    protected Creche activate(String name) throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var code = sync.newActivationCode(name);
        var result = mvc.perform(post("/sync/v1/activate").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("activationCode", code, "structureName", name,
                        "publicKey", Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()))))).andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new IllegalStateException(result.getResponse().getContentAsString());
        }
        return new Creche(UUID.fromString(body(result).get("structureId").asText()), keys);
    }

    protected MvcResult signed(Creche creche, String method, String pathAndQuery, byte[] body, Instant date) throws Exception {
        var canonical = method + "\n" + pathAndQuery + "\n" + date + "\n" + nc.ticagou.portail.common.Hashes.sha256(body);
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(creche.keys().getPrivate());
        signer.update(canonical.getBytes(StandardCharsets.UTF_8));
        var request = ("GET".equals(method) ? get(pathAndQuery) : post(pathAndQuery).contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .header("X-Ticagou-Structure", creche.id().toString())
                .header("X-Ticagou-Date", date.toString())
                .header("X-Ticagou-Signature", Base64.getEncoder().encodeToString(signer.sign()));
        return mvc.perform(request).andReturn();
    }

    protected MvcResult batch(Creche creche, Object batch) throws Exception {
        return signed(creche, "POST", "/sync/v1/batch", json.writeValueAsBytes(batch), Instant.now());
    }

    protected JsonNode fixture(String name) throws Exception {
        return json.readTree(Files.readAllBytes(CONTRACT.resolve(name)));
    }

    // --- Parents ------------------------------------------------------------------------------

    /** Connexion par le code reçu ; renvoie le cookie de session. */
    protected jakarta.servlet.http.Cookie login(String email) throws Exception {
        MAILS.clear();
        mvc.perform(post("/api/v1/auth/request").header("X-Ticagou-Portail", "1").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("email", email)))).andReturn();
        var mail = awaitMail();
        var result = mvc.perform(post("/api/v1/auth/verify").header("X-Ticagou-Portail", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("email", email, "code", code(mail))))).andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new IllegalStateException(result.getResponse().getContentAsString());
        }
        return result.getResponse().getCookie("ticagou_session");
    }

    protected static SimpleMailMessage awaitMail() throws InterruptedException {
        var deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (MAILS.isEmpty() && Instant.now().isBefore(deadline)) {
            Thread.sleep(20);
        }
        if (MAILS.isEmpty()) {
            throw new IllegalStateException("Aucun e-mail envoyé");
        }
        return MAILS.getFirst();
    }

    protected static String code(SimpleMailMessage mail) {
        var m = java.util.regex.Pattern.compile("code dans l'application : (\\d{3}) (\\d{3})").matcher(mail.getText());
        if (!m.find()) {
            throw new IllegalStateException(mail.getText());
        }
        return m.group(1) + m.group(2);
    }

    protected static String token(SimpleMailMessage mail) {
        var m = java.util.regex.Pattern.compile("#jeton=([A-Za-z0-9_-]+)").matcher(mail.getText());
        if (!m.find()) {
            throw new IllegalStateException(mail.getText());
        }
        return m.group(1);
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    protected static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }
}
