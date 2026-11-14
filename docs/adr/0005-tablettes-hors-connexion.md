# ADR 0005 — Tablettes appairées, pointage hors connexion et temps réel

- **Statut** : acceptée
- **Date** : 31/10/2026, mise à jour le 15/11/2026

## Contexte

La tablette d'accueil pointe les arrivées et départs des enfants et du personnel, toute la journée, sur le Wi-Fi de la
crèche. Internet peut couper : les enfants continuent d'arriver et de partir, et le départ doit rester bloqué si la
personne n'est pas autorisée. Le taux d'encadrement doit s'afficher en direct sur la tablette et au tableau de bord.
La tablette est un navigateur (`/kiosk`) ou l'application Ti'Cagou Pointage ([ADR 0023](0023-applications-mobiles-capacitor.md)).

## Décision

**Appairage**
- La direction crée la tablette dans Paramètres › Tablettes et abonnement et obtient un code à 6 chiffres, valable
  10 minutes, et son QR code ([ADR 0008](0008-qr-code-appairage.md)). La tablette l'échange (`POST /devices/pair`)
  contre un secret aléatoire de 256 bits ; seule son empreinte SHA-256 est stockée. 5 codes faux en 10 minutes
  bloquent l'adresse.
- La tablette garde son secret (navigateur : stockage local ; application : Keystore Android) et obtient un **jeton
  d'appareil** court (`POST /devices/session`, JWT HS256, rôle `APPAREIL`, pas d'utilisateur). Retirer la tablette
  invalide le secret ; chaque appel d'appareil relit la tablette en base, un jeton encore valide d'une tablette retirée
  est refusé.
- La connexion par PIN et la liste des personnes à PIN exigent ce jeton.

**File d'attente (outbox)**
- Sur la tablette, **toute** action (arrivée, consignes, départ, pointage du personnel, transmission…) devient une
  opération avec un `clientId` aléatoire, rangée dans IndexedDB, appliquée tout de suite à l'état local, puis envoyée
  dans l'ordre (`POST /kiosk/operations`, jeton d'appareil). En ligne comme hors ligne, c'est le même chemin.
- La file et l'instantané du jour sont **chiffrés** (AES-256-GCM, clé WebCrypto non extractible, `kiosk/secureStore`).
  Sans stockage disponible, ils restent en mémoire, jamais en clair sur le disque.
- Le serveur applique chaque opération dans sa transaction et garde son résultat (`kiosk_operation`) : un lot renvoyé
  ne crée pas de doublon. Le contenu n'est pas conservé (il peut contenir un PIN).
- Auteur : la session PIN en ligne (`sessionId`, famille du jeton de rafraîchissement), ou, hors ligne, une opération
  `CONNEXION_HORS_LIGNE` portant le PIN, vérifié à la réception.
- **Un fait réel n'est jamais perdu** : un code faux saisi hors connexion (connexion, pointage du personnel, validation
  de départ par la directrice) garde le pointage, le marque « à vérifier » et le journalise.
- Conflits : arrivée d'un enfant déjà présent → « déjà reçue » ; départ sans arrivée → refusé et listé pour la
  direction ; heure de tablette incohérente → refusée.
- L'instantané du jour (`GET /kiosk/today` : enfants, santé, personnes autorisées, personnel, part d'encadrement de
  chaque enfant) est gardé sur la tablette pour pointer et recalculer l'encadrement hors connexion.

**Départ non autorisé** : écran rouge, PIN de la directrice sur place, ou demande d'accord à distance sur le téléphone de
la direction ([ADR 0026](0026-notifications-natives.md)) ; la décision est tracée.

**Temps réel**
- Flux **SSE** (`GET /staffing/stream`, `SseEmitter`) : événements `ratio`, `attendance`, `alert`, `ping`. Recalcul
  après chaque transaction validée et chaque minute.
- Lu par `fetch` en flux plutôt que par `EventSource`, qui ne sait pas envoyer l'en-tête `Authorization`.

**PWA** : manifeste et service worker écrits à la main (`web/public/`), sans dépendance : réseau d'abord pour la page,
cache pour `/assets/`. L'instance sert le build du front, avec retour à `index.html`.

## Limites assumées

- Hors connexion, une tablette ne voit que ses propres pointages : le taux d'encadrement affiché est incomplet. Si la
  coupure commence avant l'ouverture, la tablette n'a pas l'instantané du jour.
- Les données du jour (santé, téléphones des parents) sont gardées chiffrées sur la tablette ; elles sont effacées au
  changement de jour et quand la tablette n'est plus appairée.
- Le PIN seul (sans toucher son nom) n'est possible qu'en ligne : la tablette ne connaît pas les codes.

## Alternatives écartées

- **Session de l'utilisateur pour la resynchronisation** : rien ne part si personne n'est connecté, ou si la page a
  été rechargée.
- **Copie des empreintes de PIN sur la tablette** : un code à 4 chiffres se retrouve en quelques secondes.
- **WebSocket** : bidirectionnel inutile ici ; SSE passe par le même filtre de sécurité que l'API REST.
- **Workbox / vite-plugin-pwa** : dépendances de build pour un service worker de quelques lignes.
