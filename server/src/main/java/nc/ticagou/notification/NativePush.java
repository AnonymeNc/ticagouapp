package nc.ticagou.notification;

import java.time.Duration;

/** Types communs aux envois APNs et FCM (ADR 0026). */
public final class NativePush {

    private NativePush() {
    }

    /**
     * Une notification : un titre et une phrase, jamais de donnée de santé ni de contenu de message.
     *
     * @param title    titre court
     * @param body     une phrase
     * @param link     écran de l'application à ouvrir (« /presences »), transmis en donnée
     * @param category catégorie choisie par la personne (DEPART, ENCADREMENT, SAUVEGARDE, TABLETTE)
     * @param ttl      durée de vie chez Apple ou Google
     * @param collapse identifiant de regroupement : une alerte toujours ouverte n'est pas empilée
     */
    public record Message(String title, String body, String link, String category, Duration ttl, String collapse) {
    }

    /** Appareil destinataire : jeton du service, plateforme, application (identifiant du paquet). */
    public record Target(String token, Platform platform, String bundleId) {
    }

    public enum Platform { IOS, ANDROID }

    /** OK ; INVALID : jeton refusé par le service (à effacer) ; FAILED : erreur passagère ou de configuration. */
    public enum Result { OK, INVALID, FAILED }

    public interface Sender {
        boolean enabled();

        Result send(Message message, Target target);
    }
}
