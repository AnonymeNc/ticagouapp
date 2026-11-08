package nc.ticagou.console;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages de la console. {@code secretKey} : clé (Base64, 32 octets) qui chiffre les secrets TOTP ;
 * {@code setupToken} : jeton à usage unique du premier compte ; {@code sessionMaxHours} : durée maximale d'une session.
 */
@ConfigurationProperties(prefix = "console")
public record ConsoleProperties(String secretKey, String setupToken, boolean secureCookie, int sessionMaxHours) {
}
