package nc.ticagou.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import nc.ticagou.IntegrationTest;

class AuditIT extends IntegrationTest {

    @Autowired
    ChildAccessLogger childAccessLogger;

    @Autowired
    JwtDecoder jwtDecoder;

    String token;

    @BeforeEach
    void installer() throws Exception {
        token = install();
    }

    @Test
    void journalDesConsultationsDeDossiersEnfants() throws Exception {
        var kaina = UUID.randomUUID();
        var noa = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwtDecoder.decode(token)));
        try {
            childAccessLogger.log(kaina, ChildAccessContext.FICHE);
            childAccessLogger.log(kaina, ChildAccessContext.SANTE);
            childAccessLogger.log(noa, ChildAccessContext.FICHE);
        } finally {
            SecurityContextHolder.clearContext();
        }

        var all = body(getAs(token, "/api/v1/audit/child-access"));
        assertThat(all.get("total").asLong()).isEqualTo(3);

        var forKaina = body(getAs(token, "/api/v1/audit/child-access?childId=" + kaina));
        assertThat(forKaina.get("total").asLong()).isEqualTo(2);
        assertThat(forKaina.at("/items/0/context").asText()).isEqualTo("SANTE");
        assertThat(forKaina.at("/items/0/userName").asText()).isEqualTo("Nathalie Kaloï");
    }

    @Test
    void journalDAuditFiltrableParAction() throws Exception {
        login(DIRECTOR_LOGIN, DIRECTOR_PASSWORD);

        var page = body(getAs(token, "/api/v1/audit/events?action=LOGIN_SUCCEEDED"));

        // Une connexion à l'installation, une ici.
        assertThat(page.get("total").asLong()).isEqualTo(2);
        assertThat(page.at("/items/0/actorName").asText()).isEqualTo("Nathalie Kaloï");
        assertThat(page.at("/items/0/clientIp").asText()).isEqualTo("127.0.0.1");
    }

    @Test
    void lesJournauxSontEnAjoutSeul() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwtDecoder.decode(token)));
        try {
            childAccessLogger.log(UUID.randomUUID(), ChildAccessContext.FICHE);
        } finally {
            SecurityContextHolder.clearContext();
        }

        for (var table : List.of("audit_event", "child_record_access")) {
            assertThatThrownBy(() -> jdbc.update("DELETE FROM " + table))
                    .hasMessageContaining("ajout seul");
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_event SET action = 'LOGOUT'"))
                .hasMessageContaining("ajout seul");
    }
}
