package nc.ticagou.notification;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Notifications natives des applications (ADR 0026). Les secrets viennent du socle (comptes développeur de l'éditeur) ;
 * sans eux, les notifications natives sont simplement désactivées.
 *
 * @param apnsKey           clé .p8 d'Apple (PKCS#8, Base64 du DER ou PEM) ; vide = pas d'APNs
 * @param apnsKeyId         identifiant de la clé (kid)
 * @param apnsTeamId        identifiant de l'équipe Apple (iss)
 * @param apnsProduction    serveur de production d'Apple ; faux = bac à sable (builds de développement)
 * @param apnsUrl           adresse du service APNs ; vide = celle d'Apple (les tests la remplacent)
 * @param fcmServiceAccount fichier JSON du compte de service Firebase, en clair ou en Base64 ; vide = pas de FCM
 * @param fcmUrl            adresse de FCM ; vide = celle de Google (les tests la remplacent)
 * @param ratioDelay        encadrement insuffisant depuis cette durée : la direction est prévenue
 * @param tabletOffline     tablette sans nouvelles depuis cette durée, pendant l'ouverture : la direction est prévenue
 */
@ConfigurationProperties(prefix = "ticagou.push")
public record PushProperties(
        @DefaultValue("") String apnsKey,
        @DefaultValue("") String apnsKeyId,
        @DefaultValue("") String apnsTeamId,
        @DefaultValue("true") boolean apnsProduction,
        @DefaultValue("") String apnsUrl,
        @DefaultValue("") String fcmServiceAccount,
        @DefaultValue("") String fcmUrl,
        @DefaultValue("5m") Duration ratioDelay,
        @DefaultValue("30m") Duration tabletOffline) {

    public boolean apnsEnabled() {
        return !apnsKey.isBlank() && !apnsKeyId.isBlank() && !apnsTeamId.isBlank();
    }

    public boolean fcmEnabled() {
        return !fcmServiceAccount.isBlank();
    }
}
