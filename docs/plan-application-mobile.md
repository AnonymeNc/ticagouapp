# Plan — Applications mobiles Ti'Cagou (Capacitor)

- **Statut** : en cours (phase 2, socle)
- **Date** : 14/11/2026
- **Source** : [étude](etude-application-mobile.md), option B retenue
- **Exigence** : l'application de la direction est la même sur téléphone, tablette **et ordinateur** (navigateur).
  Un seul code React pour les trois.

## 0. Cible

| Application | Plateformes | Base de code | Utilisateurs |
|---|---|---|---|
| **Ti'Cagou Direction** | iOS, Android, navigateur (ordinateur) | `web/` (responsive) | DIRECTION, COMPTABILITE, EDUCATRICE, CUISINE selon droits |
| **Ti'Cagou Pointage** | tablette Android (iPad en option), navigateur `/kiosk` | `web/src/features/kiosk` | tablette appairée, PIN |
| **Ti'Cagou Parents** | iOS, Android, PWA | `parents-portal/web` | parents invités |

Le serveur, le portail, la console et `deploy/` restent ; ils évoluent pour les jetons, le CORS, l'annuaire et les
notifications natives. Toutes les règles métier du CLAUDE.md s'appliquent sans exception.

## 1. Décisions à faire trancher par l'utilisateur (avant la phase concernée)

1. Un téléphone de direction compte-t-il comme un PC dans l'abonnement ? (phase 2)
   → **Tranché le 14/11/2026 : non**, compté à part, plafond de 10 téléphones ([ADR 0028](adr/0028-postes-mobiles-et-abonnement.md)).
2. Distribution de Direction et Pointage : store public ou distribution privée (Apple Business Manager, Managed Google
   Play) ? (phase 8)
3. Compte développeur Apple / Google : au nom de quelle entité ? Créés **par l'utilisateur** (Claude ne crée pas de
   compte ni ne paie). (phase 8)
4. Builds iOS : Mac disponible, ou CI macOS (GitHub Actions, Codemagic) ? (phase 2)
   → **Tranché le 14/11/2026 : CI macOS GitHub Actions** (suppose le dépôt GitHub, décision 6).
5. iPad pris en charge pour le pointage, ou Android seulement ? (phase 6)
6. Création d'un dépôt GitHub pour `ticagouapp` (aujourd'hui local seulement). (dès que l'utilisateur le souhaite)

## 2. Phases

Chaque phase : plan court, branche dédiée, tests verts (`./mvnw verify`, `pnpm test`, `pnpm typecheck`), commit.
Les maquettes d'une phase sont validées par l'utilisateur avant le développement qui en dépend.

### Phase 0 — ADR et cadrage
- ADR 0023 **Applications mobiles avec Capacitor** : dépendance, version, plugins retenus, structure du dépôt
  (proposition : `mobile/direction`, `mobile/pointage`, `mobile/parents`, chacun avec son `capacitor.config.ts`, qui
  embarque le build de `web/` ou de `parents-portal/web`).
- ADR 0024 **Authentification des applications** : jeton porteur + rafraîchissement en stockage sécurisé, cookie
  conservé pour le navigateur, CORS limité aux origines Capacitor, déverrouillage biométrique, double authentification
  inchangée. Met à jour les ADR 0002 et 0013.
- ADR 0025 **Choix de la crèche** : annuaire tenu par la console éditeur ([ADR 0017](adr/0017-console-editeur.md)),
  code de crèche ou QR depuis Paramètres → adresse de l'instance ; plusieurs crèches sur un même téléphone.
- ADR 0026 **Notifications natives** : APNs (jeton ES256) et FCM HTTP v1 (compte de service, RS256) avec le JDK seul,
  à côté de Web Push ; catégories parents ; alertes direction (encadrement, départ bloqué, sauvegarde en échec).
- ADR 0027 **Distribution et mises à jour** : stores ou privé, version minimale exigée par l'instance, contenu web
  embarqué vs mis à jour à chaud, compatibilité `/api/v1`.
- ADR 0028 **Postes et abonnement** selon la décision 1 (modifie l'ADR 0011 / 0021).

### Phase 1 — Maquettes (`docs/design/`, même charte et mêmes jetons que l'existant)
- **Direction, téléphone** : navigation basse + menu, tableau de bord (encadrement en direct), présences du jour,
  fiche enfant (santé toujours visible), départ bloqué à valider, messages, repas, facturation (consultation +
  validation), personnel, paramètres essentiels, choix de la crèche, connexion + 2FA + biométrie.
- **Direction, tablette et ordinateur** : points de rupture, tableaux → cartes, formulaires longs, dialogues → feuilles.
- **Pointage natif** : appairage par scan QR, mode kiosque, état hors connexion.
- **Parents** : compléter `Ti'Cagou — app parents mobile.html` (accueil, autorisation des notifications,
  suppression du compte, verrouillage).
- Signaler tout écart avec les maquettes existantes.

### Phase 2 — Socle technique
- Capacitor dans `mobile/*`, scripts de build (`pnpm`), icônes et écrans de démarrage.
- Front : adresse d'API configurable (aujourd'hui même origine), détection de plateforme, adaptateur
  d'authentification (cookie / porteur), stockage sécurisé, identifiant de poste (`lib/workstation.ts`).
- Serveur : CORS, émission du rafraîchissement par corps de réponse pour les applications, version minimale, tests.
- CI des builds Android (et iOS selon décision 4).

### Phase 3 — Direction responsive (navigateur et applications)
- `AppShell`, `Sidebar`, `Topbar` responsive ; composants de liste/tableau adaptatifs dans `components/`.
- Fonction par fonction (`web/src/features/*`) : dashboard, attendance, children, health, meals, staff, staffing,
  enrollment, contracts, billing, accounting, reporting, portal, settings. Import, setup, sauvegardes : utilisables,
  marqués « recommandé sur ordinateur ».
- Tests Vitest aux tailles téléphone et bureau ; captures avant / après.

### Phase 4 — Choix de la crèche et annuaire
- Console : annuaire (code → adresse publique), alimenté par `creche.sh` / la console.
- Instance : QR « Ouvrir sur mon téléphone » dans Paramètres.
- Application : liste des crèches connues, changement de crèche, oubli d'une crèche (efface ses données locales).

### Phase 5 — Notifications natives
- Serveur de crèche (alertes direction) et portail (parents) : enregistrement des appareils, envoi APNs / FCM, effacement
  des jetons refusés, tests avec vecteurs connus. Aucune donnée de santé dans une notification.

### Phase 6 — Pointage natif
- Mode kiosque Android (lock task), démarrage automatique, scan QR natif, clé du `secureStore` dans le Keystore, file
  d'attente durable, caméra et vidéo natives. iPad : Accès guidé documenté (selon décision 5).

### Phase 7 — Parents natif
- Coque Capacitor de `parents-portal/web`, notifications natives, suppression du compte dans l'application (efface
  l'accès au portail et prévient la crèche, qui purge son dossier par `PURGE_FAMILY` selon ses obligations ; tranché le
  14/11/2026), verrouillage optionnel. La PWA reste.

### Phase 8 — Sécurité, conformité, publication
- Revue sécurité (`/security-review`) : stockage local, captures d'écran, téléphone perdu (révocation des appareils par
  la direction), journalisation.
- Fiches Apple Privacy / Google Data safety, politique de confidentialité, information des familles (Apple, Google
  sous-traitants des notifications).
- Bêta TestFlight / test interne Google, compte de démonstration pour la relecture, publication.

### Phase 9 — Documentation, formation, commercial, captures
- **ADR** : à jour à chaque phase ; marquer les ADR remplacés ou modifiés.
- **Wiki** (`docs/wiki/`) : installer l'application Direction (téléphone, ordinateur), choisir sa crèche, appairer une
  tablette avec l'application Pointage, mode kiosque, application Parents, notifications, téléphone perdu, dépannage.
  Mettre à jour `04-tablettes.md`, `08-portail-nouvelle-creche.md`, `EXPLOITATION.md` (publication d'une version).
- **Formation** : mettre à jour `12-formation-direction.md`, `13-formation-equipe.md` ; ajouter un guide parents
  (une page, imprimable) et un déroulé de session de formation avec exercices.
- **Commercial** (`docs/commercial/`) : argumentaire « Ti'Cagou dans la poche », prospectus mis à jour, textes des
  fiches stores (FR), scénario de démonstration mobile, mise à jour de `checklist-commercialisation.md`.
- **Captures** : écrans réels tirés de l'application (navigateur de prévisualisation aux tailles téléphone, tablette,
  bureau ; émulateur si disponible), données de démonstration uniquement, rangées dans `docs/commercial/captures/` et
  aux formats exigés par les stores.

## 3. Avancement

| Phase | État |
|---|---|
| 0 ADR | fait le 14/11/2026 : ADR 0023 à 0028 ; ADR 0002, 0005, 0011, 0013, 0017, 0018, 0021 marqués |
| 1 Maquettes | validées le 14/11/2026 (écarts acceptés : barre basse + « Plus », rail 768–1 279 px, code e-mail parents) : `docs/design/Ti'Cagou — Direction mobile.html`, `… Direction tablette et ordinateur.html`, `… Pointage natif.html`, `… app parents mobile, compléments.html` (charte : `charte-mobile.css`) |
| 2 Socle | à faire |
| 3 Direction responsive | à faire |
| 4 Annuaire | à faire |
| 5 Notifications | à faire |
| 6 Pointage | à faire |
| 7 Parents | à faire |
| 8 Publication | à faire |
| 9 Docs, formation, commercial, captures | à faire (au fil de l'eau) |
