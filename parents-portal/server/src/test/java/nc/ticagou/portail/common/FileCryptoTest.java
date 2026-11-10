package nc.ticagou.portail.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import nc.ticagou.portail.PortalProperties;

/** Chiffrement au repos des fichiers du portail : une clé changée donne un message clair, pas une erreur 500. */
class FileCryptoTest {

    private static FileCrypto crypto(byte fill) {
        var key = new byte[32];
        java.util.Arrays.fill(key, fill);
        return new FileCrypto(new PortalProperties(null, null, Base64.getEncoder().encodeToString(key), null, false, false, null, null));
    }

    @Test
    void fichierRelisibleAvecLaMemeCleEtRefuseClairementSinon() {
        var structure = UUID.randomUUID();
        var item = UUID.randomUUID();
        var stored = crypto((byte) 1).encrypt(structure, item, "affiche".getBytes(StandardCharsets.UTF_8));

        assertThat(new String(crypto((byte) 1).decrypt(structure, item, stored), StandardCharsets.UTF_8)).isEqualTo("affiche");

        assertThatThrownBy(() -> crypto((byte) 2).decrypt(structure, item, stored)).as("clé changée (PORTAL_FILE_KEY)")
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.GONE);
                    assertThat(e.code()).isEqualTo("fichier_illisible");
                    assertThat(e.getMessage()).contains("demandez à la crèche");
                });
        assertThatThrownBy(() -> crypto((byte) 1).decrypt(structure, UUID.randomUUID(), stored)).as("servi à la place d'un autre")
                .isInstanceOf(ApiException.class);
    }
}
