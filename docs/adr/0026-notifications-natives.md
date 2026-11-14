# ADR 0026 — Notifications natives (APNs et FCM)

- **Statut** : acceptée
- **Date** : 14/11/2026
- **Modifie** : [ADR 0018](0018-notifications-push-portail-parents.md) (Web Push reste ; s'y ajoutent APNs et FCM pour les applications)
- **S'appuie sur** : [ADR 0023](0023-applications-mobiles-capacitor.md), [ADR 0024](0024-authentification-applications.md)

## Contexte

Le portail envoie des notifications Web Push aux parents (ADR 0018), sans dépendance : le JDK fournit la cryptographie
et le client HTTP. Les applications natives ne reçoivent pas le Web Push : sur iPhone, une notification passe par
**APNs** (Apple) ; sur Android, par **FCM** (Google). La direction veut aussi être prévenue sur son téléphone de ce qui
demande une action immédiate.

Les SDK serveur (Firebase Admin, Pushy, java-apns) tirent des dizaines de dépendances (gRPC, Guava, Netty…). Les deux
services ont une API HTTP simple, authentifiée par un JWT.

## Décision

**Deux transports de plus, JDK seul**, à côté de `WebPushTransport` :

| Service | Authentification | Envoi |
|---|---|---|
| **APNs** | JWT **ES256** signé avec la clé `.p8` de l'équipe Apple (`kid`, `iss` = Team ID), renouvelé toutes les 50 minutes | `POST https://api.push.apple.com/3/device/<jeton>` en HTTP/2 (`java.net.http`), en-têtes `apns-topic`, `apns-push-type: alert`, `apns-expiration` |
| **FCM HTTP v1** | JWT **RS256** signé avec la clé du compte de service, échangé contre un jeton OAuth 2 (`oauth2.googleapis.com/token`), gardé jusqu'à son échéance | `POST https://fcm.googleapis.com/v1/projects/<projet>/messages:send` |

- La signature ES256 est produite directement au format JOSE (`r‖s`, 64 octets) par l’algorithme
  `SHA256withECDSAinP1363Format` du JDK : pas de conversion depuis le DER. Les tests vérifient chaque jeton avec la clé
  publique (`NativeSendersTest`).
- Les tests n'appellent jamais Apple ni Google : un faux serveur HTTP vérifie requêtes, en-têtes et gestion des
  réponses.
- Pas de Firebase SDK dans l'application iOS : `@capacitor/push-notifications` donne le jeton APNs, envoyé tel quel.
  L'application Android contient le minimum Firebase Messaging que le plugin impose (`google-services.json`).

**Secrets** (au niveau du socle, créés par l'éditeur avec ses comptes développeur ; guide : [PUBLIER-LES-APPLICATIONS.md](../PUBLIER-LES-APPLICATIONS.md))
- `APNS_KEY` (PKCS#8 Base64), `APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_PRODUCTION` ; `FCM_SERVICE_ACCOUNT` (JSON Base64).
- Dans `socle.env` et le coffre ; `instance.yml` et `socle.yml` les passent aux instances et au portail (lus à
  chaque création du conteneur). Sans ces valeurs, les notifications natives sont désactivées et le reste fonctionne.
- Le portail est un projet Maven séparé : les classes d’envoi (`JwtSigner`, `ApnsSender`, `FcmSender`, `NativePush`,
  `PushProperties`) y sont recopiées dans `portail.push.natif`, à garder identiques.

**Enregistrement des appareils**
- Instance : `POST /api/v1/notifications/devices` (`{plateforme: IOS|ANDROID, jeton, application, categories}`),
  rattaché à l'utilisateur et au poste (ADR 0024). `DELETE` à la déconnexion et à l'oubli de la crèche.
- Portail : même chose, rattaché à la session du parent, à côté des abonnements Web Push (même table de catégories).
- Un jeton refusé par le service (APNs 410 / `BadDeviceToken`, FCM `UNREGISTERED` / 404) est effacé. Il l'est aussi
  quand le poste est retiré, l'utilisateur désactivé ou l'accès du parent supprimé.
- Table `notification_device` côté instance, avec `structure_id`.

**Parents** : mêmes catégories et mêmes déclencheurs que l'ADR 0018 (photos, messages, actualités, documents à
signer, factures). Un parent qui a l'application et la PWA reçoit sur chaque appareil abonné.

**Direction** : alertes, réglables par personne dans l'application (Paramètres › Notifications) :

| Alerte | Destinataires par défaut | Texte |
|---|---|---|
| Encadrement insuffisant (taux dépassé plus de 5 minutes) | DIRECTION | « Encadrement insuffisant — Les Tortues : 1 adulte pour 7 » |
| Départ bloqué à valider à distance | DIRECTION | « Départ à valider : Maëlys D. » |
| Sauvegarde en échec | DIRECTION | « La sauvegarde de cette nuit a échoué » |
| Pointage hors connexion depuis plus de 30 minutes | DIRECTION | « Tablette Accueil hors connexion depuis 30 min » |

Les seuils sont des réglages de l'instance (`ticagou.push.ratio-delay`, `tablet-offline`), pas des constantes. Une même
alerte n'est pas renvoyée tant qu'elle reste ouverte (`ratio_breach.notified_at`, `device.offline_notified_at`).

**Départ validé à distance** (maquette B5) : jusqu'ici, seul le code PIN d'une directrice présente débloquait un départ
avec une personne absente de la liste. La tablette peut désormais demander l'accord de la direction
(`departure_request`) ; la direction est prévenue, autorise ou refuse avec un motif obligatoire, et l'autorisation
enregistre le départ comme une validation de la direction au bureau (`departure_override`, journal). La tablette suit la
réponse ; hors connexion, seul le code sur place reste possible. La règle « blocage + validation direction tracée » est
inchangée.

**Contenu : aucune donnée de santé**, aucun contenu de message, aucun nom de la personne venue chercher l'enfant.
Prénom et initiale de l'enfant seulement, comme l'ADR 0018. Le texte passe en clair chez Apple et Google (contrairement
au Web Push chiffré de bout en bout) : c'est une raison de plus de n'y mettre que l'invitation à ouvrir l'application.

## Alternatives écartées

- **Firebase Admin SDK / bibliothèques APNs** : dizaines de dépendances transitives pour deux requêtes HTTP signées.
- **FCM pour iOS aussi** : impose le SDK Firebase dans l'application iPhone et fait passer les notifications des
  parents iPhone par Google en plus d'Apple.
- **Relais unique (le portail envoie pour les instances)** : les alertes de la direction dépendraient du portail, qui
  est optionnel. Les instances tournent sur le même hôte que le portail : partager les secrets du socle n'élargit
  presque pas la surface d'attaque.
- **Service tiers (OneSignal, Pusher)** : un sous-traitant de plus qui verrait les jetons et les textes.

## Conséquences

- Apple et Google deviennent sous-traitants des notifications : à déclarer dans l'information des familles et la
  politique de confidentialité ([commercial/stores](../commercial/stores/politique-confidentialite.md)).
- Le navigateur de la direction n'a pas de notification (le tableau de bord est en temps réel) ; Web Push pour la
  direction pourra s'ajouter plus tard sur le même modèle.
- La rotation de la clé APNs ou du compte de service se fait dans `socle.env` puis `creche.sh mettre-a-jour --toutes`.
