# ADR 0005 — Tablettes appairées, pointage hors connexion et temps réel

- **Statut** : acceptée, complétée par l'[ADR 0013](0013-web-heberge-instance-par-creche.md) (HTTPS public, stockage chiffré) et par l'[ADR 0023](0023-applications-mobiles-capacitor.md) (application Pointage native, clé dans le Keystore)
- **Date** : 31/10/2026

## Contexte

La tablette d'accueil pointe les arrivées et départs des enfants et du personnel, toute la journée, sur le
Wi-Fi de la crèche. Le serveur local peut redémarrer ou devenir injoignable quelques minutes : les enfants
continuent d'arriver et de partir, et le départ doit rester bloqué si la personne n'est pas autorisée.
Le taux d'encadrement doit s'afficher en direct sur la tablette et au tableau de bord.

## Décision

**Appairage**
- La direction crée la tablette dans Paramètres › Appareils et obtient un code à 6 chiffres, valable 10 minutes.
  La tablette l'échange (`POST /devices/pair`) contre un secret aléatoire de 256 bits ; seule son empreinte
  SHA-256 est stockée. 5 codes faux en 10 minutes bloquent l'adresse.
- La tablette garde son secret dans le stockage local et obtient un **jeton d'appareil** court
  (`POST /devices/session`, JWT HS256, rôle `APPAREIL`, pas d'utilisateur). Retirer la tablette invalide le secret ;
  chaque appel d'appareil relit la tablette en base, un jeton encore valide d'une tablette retirée est refusé.
- La connexion par PIN et la liste des personnes à PIN exigent ce jeton (annoncé par l'ADR 0002).

**File d'attente (outbox)**
- Sur la tablette, **toute** action (arrivée, consignes, départ, pointage du personnel…) devient une opération
  avec un `clientId` aléatoire, rangée dans IndexedDB, appliquée tout de suite à l'état local, puis envoyée
  dans l'ordre (`POST /kiosk/operations`, jeton d'appareil). En ligne comme hors ligne, c'est le même chemin.
- Le serveur applique chaque opération dans sa transaction et garde son résultat (`kiosk_operation`) :
  un lot renvoyé ne crée pas de doublon. Le contenu n'est pas conservé (il peut contenir un PIN).
- Auteur : la session PIN en ligne (`sessionId`, famille du jeton de rafraîchissement), ou, hors ligne, une
  opération `CONNEXION_HORS_LIGNE` portant le PIN, vérifié à la réception.
- **Un fait réel n'est jamais perdu** : un code faux saisi hors connexion (connexion, pointage du personnel,
  validation de départ par la directrice) garde le pointage, le marque « à vérifier » et le journalise.
- Conflits : arrivée d'un enfant déjà présent → « déjà reçue » (les opérations suivantes rejoignent le passage
  ouvert) ; départ sans arrivée → refusé et listé pour la direction ; heure de tablette incohérente → refusée.
- L'instantané du jour (`GET /kiosk/today` : enfants, santé, personnes autorisées, personnel, part d'encadrement
  de chaque enfant) est gardé sur la tablette pour pointer et recalculer l'encadrement hors connexion.

**Temps réel**
- Flux **SSE** (`GET /staffing/stream`, `SseEmitter`) : événements `ratio`, `attendance`, `alert`, `ping`.
  Recalcul après chaque transaction validée et chaque minute.
- Lu par `fetch` en flux plutôt que par `EventSource`, qui ne sait pas envoyer l'en-tête `Authorization`.

**PWA**
- Manifeste et service worker écrits à la main (`desktop/public/`), sans dépendance : réseau d'abord pour la page,
  cache pour `/assets/`. Le serveur sert le build du front (`ticagou.web.root`), avec retour à `index.html`.

## Limites assumées

- **HTTPS reporté à l'installeur** (fait : [ADR 0009](0009-https-lan-mdns.md)). Un service worker et WebCrypto exigent un contexte sécurisé : sur
  `http://serveur-de-la-creche:8080`, la PWA ne s'installe pas et la page ne se recharge pas hors connexion.
  La file d'attente (IndexedDB) fonctionne tant que la page reste ouverte. Le certificat local et son
  installation sur les tablettes viendront avec l'installeur ; le serveur accepte déjà un keystore (`server.ssl`).
- Les PIN restent **en clair dans la file locale** jusqu'à leur envoi (pas de WebCrypto sans HTTPS).
- Les données du jour (santé, téléphones des parents) sont gardées sur la tablette ; elles sont effacées au
  changement de jour et quand la tablette n'est plus appairée.
- Le PIN seul (sans toucher son nom) n'est possible qu'en ligne : la tablette ne connaît pas les codes.

## Alternatives écartées

- **Session de l'utilisateur pour la resynchronisation** : rien ne part si personne n'est connecté, ou si la page
  a été rechargée ; la liste des PIN reste publique.
- **Copie des empreintes de PIN sur la tablette** : un code à 4 chiffres se retrouve en quelques secondes.
- **WebSocket** : bidirectionnel inutile ici ; SSE passe par le même filtre de sécurité que l'API REST.
- **Workbox / vite-plugin-pwa** : dépendances de build pour un service worker de quelques lignes.
