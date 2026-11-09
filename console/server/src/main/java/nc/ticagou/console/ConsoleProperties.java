package nc.ticagou.console;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages de la console.
 *
 * @param secretKey        clé (Base64, 32 octets) qui chiffre les secrets TOTP
 * @param setupToken       jeton à usage unique du premier compte
 * @param secureCookie     cookies {@code Secure} (faux seulement en développement, sans HTTPS)
 * @param sessionMaxHours  durée maximale d'une session
 * @param portalUrl        adresse interne du portail, qui héberge la supervision (http://portail:8090)
 * @param portalAdminToken jeton d'administration du portail ({@code PORTAL_ADMIN_TOKEN}) ; vide : supervision non lue
 * @param hostToken        jeton du rapport de l'hôte ({@code creche.sh rapport-console}) ; vide : rapport refusé
 * @param scheduling       lectures et préparations périodiques (faux dans les tests)
 * @param mailFrom         expéditeur des factures et relances ({@code CONSOLE_MAIL_FROM}, facturation@…)
 * @param renewalLeadDays  brouillon de renouvellement d'un abonnement annuel, en jours avant la période (45)
 * @param monthlyLeadDays  brouillon de la mensualité suivante, en jours avant le mois (10)
 * @param exportsDir       exports des crèches écrits par {@code creche.sh exporter}, lus pour le téléchargement (/exports)
 */
@ConfigurationProperties(prefix = "console")
public record ConsoleProperties(String secretKey, String setupToken, boolean secureCookie, int sessionMaxHours,
                                String portalUrl, String portalAdminToken, String hostToken, boolean scheduling,
                                String mailFrom, int renewalLeadDays, int monthlyLeadDays, String exportsDir) {
}
