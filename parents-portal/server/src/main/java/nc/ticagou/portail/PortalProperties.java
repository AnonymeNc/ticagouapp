package nc.ticagou.portail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages du portail. {@code fileKey} : clé maîtresse (Base64, 32 octets) dont dérive la clé de chaque structure ;
 * {@code adminToken} : jeton de l'hébergeur pour créer les codes d'activation ; {@code supervisionEmail} : adresse
 * du prestataire qui reçoit les alertes de supervision des serveurs de crèche (ADR 0012) ; {@code vapidKey} : clés
 * des notifications Web Push (ADR 0018), vide pour les générer et les garder dans la base.
 */
@ConfigurationProperties(prefix = "portal")
public record PortalProperties(String publicUrl, String mailFrom, String fileKey, String adminToken,
                               boolean secureCookie, boolean scheduling, String supervisionEmail,
                               String vapidKey) {
}
