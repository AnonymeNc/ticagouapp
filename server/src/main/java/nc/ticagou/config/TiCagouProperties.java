package nc.ticagou.config;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Paramètres applicatifs propres à Ti'Cagou (préfixe {@code ticagou}).
 *
 * @param zone           fuseau de référence de la structure (Pacific/Noumea)
 * @param allowedOrigins origines autorisées en CORS (dev Vite ; en production, tout est sur la même origine)
 * @param appOrigins     origines des applications Capacitor (ADR 0024), ajoutées aux précédentes, sans cookie
 * @param app            versions minimales des applications mobiles (ADR 0027) et code de la crèche dans l’annuaire (ADR 0025)
 * @param security       jetons, verrouillage des comptes
 * @param storage        documents joints chiffrés sur disque
 * @param web            front servi par l'instance (navigateur et kiosque PWA des tablettes)
 * @param backup         sauvegardes chiffrées (outils PostgreSQL)
 * @param setup          création de la crèche à distance
 * @param monitoring     supervision activée d'office par l'éditeur (ADR 0013)
 * @param license        durées de l'évaluation et de la grâce, PC du bureau (ADR 0011)
 * @param demo           jeu de démonstration (PC de démonstration et développement seulement)
 */
@ConfigurationProperties(prefix = "ticagou")
public record TiCagouProperties(
        @DefaultValue("Pacific/Noumea") ZoneId zone,
        @DefaultValue("http://localhost:1420")
        List<String> allowedOrigins,
        @DefaultValue({"capacitor://localhost", "https://localhost"})
        List<String> appOrigins,
        @DefaultValue App app,
        @DefaultValue Security security,
        @DefaultValue Storage storage,
        @DefaultValue Web web,
        @DefaultValue Backup backup,
        @DefaultValue Setup setup,
        @DefaultValue Monitoring monitoring,
        @DefaultValue License license,
        @DefaultValue Demo demo) {

    /**
     * @param jwtSecret           clé HMAC en Base64 (≥ 32 octets) ; vide = clé générée et conservée en base
     * @param accessTokenTtl      durée de vie du jeton d'accès
     * @param refreshTokenTtl     durée de vie du jeton de rafraîchissement (mot de passe, PC du bureau)
     * @param appRefreshTokenTtl  durée de vie du jeton de rafraîchissement de l'application Direction, protégée par le
     *                            verrouillage biométrique du téléphone (ADR 0024)
     * @param pinRefreshTokenTtl  durée de vie du jeton de rafraîchissement (PIN, tablette)
     * @param maxFailedAttempts   échecs consécutifs avant blocage
     * @param lockDuration        durée du blocage
     * @param mfaRequired         double authentification obligatoire (direction, comptabilité) ; vrai en production
     */
    public record Security(
            @DefaultValue("") String jwtSecret,
            @DefaultValue("15m") Duration accessTokenTtl,
            @DefaultValue("7d") Duration refreshTokenTtl,
            @DefaultValue("30d") Duration appRefreshTokenTtl,
            @DefaultValue("12h") Duration pinRefreshTokenTtl,
            @DefaultValue("5") int maxFailedAttempts,
            @DefaultValue("15m") Duration lockDuration,
            @DefaultValue("false") boolean mfaRequired) {
    }

    /**
     * @param dir répertoire des fichiers chiffrés (relatif au répertoire de travail du service)
     * @param key clé AES-256 en Base64 ; vide = générée au premier démarrage et conservée en base
     */
    public record Storage(
            @DefaultValue("data/documents") java.nio.file.Path dir,
            @DefaultValue("") String key) {
    }

    /**
     * @param root répertoire du build du front ; vide = `web/dist` du dépôt, cherché depuis `server/` puis
     *             depuis la racine. Sans build, le serveur ne sert que l'API.
     */
    public record Web(@DefaultValue("") String root) {
    }

    /**
     * @param dir              dossier des archives, copié hors site par l'hébergement (deploy/)
     * @param dailyTime        heure de la sauvegarde de la nuit, étalée d'une crèche à l'autre
     * @param recoveryKey      clé de secours, gardée par l'éditeur hors de la base ; vide = générée et gardée en base
     *                         (développement)
     * @param pgBin            répertoire de pg_dump et pg_restore ; vide = recherche dans le PATH
     * @param skipPreMigration démarrer sans sauvegarde avant migration (dépannage seulement)
     */
    public record Backup(
            @DefaultValue("data/backups") java.nio.file.Path dir,
            @DefaultValue("02:00") java.time.LocalTime dailyTime,
            @DefaultValue("") String recoveryKey,
            @DefaultValue("") String pgBin,
            @DefaultValue("false") boolean skipPreMigration) {
    }

    /** @param token jeton à usage unique de l'assistant, remis à la directrice ; vide = depuis ce poste seulement (développement) */
    public record Setup(@DefaultValue("") String token) {
    }

    /**
     * @param url  service de supervision de l'éditeur ; vide = pas de supervision
     * @param code code d'activation à usage unique
     */
    public record Monitoring(@DefaultValue("") String url, @DefaultValue("") String code) {
    }

    /**
     * @param evaluationDays durée de l'évaluation sans clé, depuis l'installation
     * @param graceDays      jours après l'échéance avant le passage en lecture seule
     * @param desktopIdle    un PC du bureau (ou un téléphone) absent depuis cette durée libère sa place
     * @param mobileMax      téléphones et tablettes de la direction actifs permis, hors du compte des PC (ADR 0028)
     */
    public record License(
            @DefaultValue("30") int evaluationDays,
            @DefaultValue("30") int graceDays,
            @DefaultValue("90d") Duration desktopIdle,
            @DefaultValue("10") int mobileMax) {
    }

    /**
     * @param minDirection version minimale de l'application Direction ; en dessous, l'application demande sa mise à jour
     *                     (0.0.0 : aucune ; relevée par la configuration de la crèche, TICAGOU_APP_MIN_DIRECTION)
     * @param minPointage  version minimale de l'application Pointage ; en dessous, simple bandeau (le pointage n'est
     *                     jamais bloqué)
     * @param code         code de la crèche dans l’annuaire des applications (ADR 0025), écrit par creche.sh
     *                     (TICAGOU_INSTANCE_CODE) ; vide en développement
     */
    public record App(
            @DefaultValue("0.0.0") String minDirection,
            @DefaultValue("0.0.0") String minPointage,
            @DefaultValue("") String code) {
    }

    /** @param enabled chargement d'un jeu de démonstration permis ; jamais activé en production */
    public record Demo(@DefaultValue("false") boolean enabled) {
    }
}
