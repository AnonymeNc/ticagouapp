package nc.ticagou.health;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import nc.ticagou.audit.AuditService;
import nc.ticagou.config.SecurityConfig;
import nc.ticagou.config.TiCagouProperties;
import nc.ticagou.health.HealthResponse.DatabaseStatus;
import nc.ticagou.health.HealthResponse.Status;
import nc.ticagou.license.LicenseService;

@WebMvcTest(HealthController.class)
@Import(SecurityConfig.class)
@EnableConfigurationProperties(TiCagouProperties.class)
class HealthControllerTest {

    @Autowired
    MockMvc mvc;

    /** Intercepteur des modules de la licence, chargé par la tranche web. */
    @MockitoBean
    LicenseService licenses;

    @MockitoBean
    HealthService healthService;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    AuditService auditService;

    @Test
    void santePubliqueSansAuthentification() throws Exception {
        given(healthService.check()).willReturn(new HealthResponse(
                Status.UP, "dev", OffsetDateTime.of(2026, 10, 30, 9, 15, 0, 0, ZoneOffset.ofHours(11)),
                "Pacific/Noumea", DatabaseStatus.UP));

        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.zone").value("Pacific/Noumea"))
                .andExpect(jsonPath("$.serverTime").value("2026-10-30T09:15:00+11:00"));
    }

    @Test
    void baseIndisponibleRenvoieDegrade() throws Exception {
        given(healthService.check()).willReturn(new HealthResponse(
                Status.DEGRADED, "dev", OffsetDateTime.now(ZoneOffset.ofHours(11)),
                "Pacific/Noumea", DatabaseStatus.DOWN));

        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.database").value("DOWN"));
    }

    @Test
    void lesAutresRoutesSontProtegees() throws Exception {
        mvc.perform(get("/api/v1/children"))
                .andExpect(status().isUnauthorized());
    }
}
