package nc.ticagou.license;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

class LicenseModuleTest {

    @Test
    void unModuleEntraineCeuxDontIlABesoin() {
        assertThat(LicenseModule.withRequired(Set.of(LicenseModule.COMPTABILITE))).containsExactlyInAnyOrder(
                LicenseModule.COMPTABILITE, LicenseModule.FACTURATION, LicenseModule.INSCRIPTIONS);
        assertThat(LicenseModule.withRequired(Set.of(LicenseModule.REPAS))).containsExactly(LicenseModule.REPAS);
        assertThat(LicenseModule.withRequired(Set.of())).isEmpty();
    }
}
