package nc.ticagou.auth;

import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import nc.ticagou.config.TiCagouProperties;

/** Clé de signature HS256, encodeur/décodeur JWT et hachage Argon2id (ADR 0002). */
@Configuration
public class JwtConfig {

    static final String ISSUER = "ticagou";
    private static final String SECRET_NAME = "jwt-signing-key";
    private static final int KEY_BYTES = 32;

    @Bean
    SecretKey jwtSigningKey(TiCagouProperties properties, JdbcTemplate jdbc) {
        var configured = properties.security().jwtSecret();
        byte[] key = configured.isBlank() ? loadOrCreate(jdbc) : Base64.getDecoder().decode(configured);
        if (key.length < KEY_BYTES) {
            throw new IllegalStateException("ticagou.security.jwt-secret doit faire au moins 32 octets (Base64)");
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }

    /** Génère la clé au premier démarrage et la conserve, pour que les sessions survivent à un redémarrage. */
    private static byte[] loadOrCreate(JdbcTemplate jdbc) {
        var generated = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(generated);
        jdbc.update("INSERT INTO server_secret (name, value) VALUES (?, ?) ON CONFLICT (name) DO NOTHING",
                SECRET_NAME, generated);
        return jdbc.queryForObject("SELECT value FROM server_secret WHERE name = ?", byte[].class, SECRET_NAME);
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
        var decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER)));
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
