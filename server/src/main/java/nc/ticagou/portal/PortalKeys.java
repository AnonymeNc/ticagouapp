package nc.ticagou.portal;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Paires Ed25519 de la structure pour le service hébergé, gardées dans {@code server_secret} comme les autres
 * secrets du serveur (sauvegardes chiffrées comprises) : une pour le portail parents, une pour la supervision
 * ({@code MONITORING}, ADR 0012). Une nouvelle paire est créée à chaque activation.
 */
@Component
public class PortalKeys {

    private final JdbcTemplate jdbc;

    public PortalKeys(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Usage d'une paire : préfixe de son nom dans {@code server_secret} (50 caractères au plus avec l'identifiant). */
    public enum Use {
        PORTAL("portal"), MONITORING("monitor");

        private final String prefix;

        Use(String prefix) {
            this.prefix = prefix;
        }
    }

    /** Crée (ou remplace) la paire et renvoie la clé publique en Base64 (X.509). */
    public String rotate(UUID structureId) {
        return rotate(Use.PORTAL, structureId);
    }

    public String rotate(Use use, UUID structureId) {
        KeyPair pair = PortalSigner.newKeyPair();
        forget(use, structureId);
        jdbc.update("INSERT INTO server_secret (name, value) VALUES (?, ?)", privateName(use, structureId),
                pair.getPrivate().getEncoded());
        jdbc.update("INSERT INTO server_secret (name, value) VALUES (?, ?)", publicName(use, structureId),
                pair.getPublic().getEncoded());
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    public PrivateKey privateKey(UUID structureId) {
        return privateKey(Use.PORTAL, structureId);
    }

    public PrivateKey privateKey(Use use, UUID structureId) {
        var found = jdbc.query("SELECT value FROM server_secret WHERE name = ?", (rs, i) -> rs.getBytes(1),
                privateName(use, structureId));
        if (found.isEmpty()) {
            throw new IllegalStateException("Clé absente : réactivez le service.");
        }
        try {
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(found.getFirst()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public void forget(UUID structureId) {
        forget(Use.PORTAL, structureId);
    }

    public void forget(Use use, UUID structureId) {
        jdbc.update("DELETE FROM server_secret WHERE name IN (?, ?)", privateName(use, structureId), publicName(use, structureId));
    }

    private static String privateName(Use use, UUID structureId) {
        return use.prefix + "-key-" + structureId;
    }

    private static String publicName(Use use, UUID structureId) {
        return use.prefix + "-pub-" + structureId;
    }
}
