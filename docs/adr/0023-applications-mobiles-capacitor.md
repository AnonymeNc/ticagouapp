# ADR 0023 — Applications mobiles avec Capacitor

- **Statut** : acceptée
- **Date** : 14/11/2026
- **Étude** : [etude-application-mobile.md](../etude-application-mobile.md) (option B) · **Plan** : [plan-application-mobile.md](../plan-application-mobile.md)
- **Modifie** : [ADR 0005](0005-tablettes-hors-connexion.md) (la tablette peut être une application native, clé du stockage chiffré dans le Keystore)

## Contexte

Ti'Cagou a trois interfaces React : le back-office de la direction (`web/`), le kiosque de pointage (`web/src/features/kiosk`)
et l'application parents (`parents-portal/web`). Les crèches veulent les trouver dans les magasins d'applications,
recevoir des alertes sur le téléphone de la direction et verrouiller la tablette d'accueil sur le pointage. Une PWA ne
fait pas tout cela (notifications limitées sur iPhone, pas de vrai mode kiosque, pas de présence dans les stores).

L'étude a comparé PWA seule, Capacitor, React Native et natif. Capacitor garde environ 95 % du code : la même interface
React tourne dans une vue web native, avec des plugins pour ce que le navigateur ne fait pas. C'est une dépendance
lourde (outillage Android et iOS, projets natifs dans le dépôt) : d'où cet ADR.

## Décision

**Trois applications, un seul code par interface**

| Application | Identifiant | Contenu embarqué | Démarre sur |
|---|---|---|---|
| Ti'Cagou Direction | `nc.ticagou.direction` | build de `web/` (`vite build --mode direction`) | `/` (choix de la crèche, puis connexion) |
| Ti'Cagou Pointage | `nc.ticagou.pointage` | build de `web/` (`vite build --mode pointage`) | `/kiosk` |
| Ti'Cagou Parents | `nc.ticagou.parents` | build de `parents-portal/web` | `/` |

L'application Direction est **la même interface** que celle du navigateur sur ordinateur : aucun écran n'est écrit
deux fois. Le web reste servi par chaque instance ; les applications n'enlèvent rien au navigateur.

**Structure du dépôt**

```
mobile/
  pnpm-workspace.yaml
  direction/   package.json, capacitor.config.json, android/, ios/, www/ (généré)
  pointage/    idem, plus le plugin local KioskMode (Android)
  parents/     idem
  scripts/     build.mjs (build du front + cap sync + versions), check-versions.mjs, icones.ps1
```

- Chaque coque a son `package.json` : `cap sync` y lit les plugins à compiler dans le projet natif.
- Le front (`web/`, `parents-portal/web`) dépend de `@capacitor/core` et des mêmes plugins pour leur API JavaScript.
  `check-versions.mjs` vérifie en CI que les versions sont identiques des deux côtés.
- Les plugins sont chargés par `import()` dynamique derrière `Capacitor.isNativePlatform()` : le navigateur ne les
  télécharge pas.
- `www/` est généré, jamais commité. `android/` et `ios/` sont commités (icônes, permissions, code natif propre).
- Configuration en JSON (`capacitor.config.json`) : pas de TypeScript à installer dans les coques.
- iOS par Swift Package Manager (`ios/App/CapApp-SPM`), sans CocoaPods ni Ruby.
- Icônes et écrans de démarrage dessinés par `icones.ps1` (System.Drawing de Windows), une couleur par application :
  Direction lagon, Pointage niaouli, Parents terre.

**Version et plugins retenus** (épinglés, sans `^`)

| Paquet | Version | Usage |
|---|---|---|
| `@capacitor/core`, `cli`, `android`, `ios` | 8.6.x | socle |
| `@capacitor/app` | 8.x | cycle de vie (verrouillage au retour), bouton retour Android, liens `https://…/app` |
| `@capacitor/preferences` | 8.x | liste des crèches connues (rien de sensible) |
| `@capacitor/push-notifications` | 8.x | jeton APNs (iOS) ou FCM (Android), ADR 0026 |
| `@capacitor/splash-screen`, `@capacitor/status-bar` | 8.x | démarrage, barre d'état |
| `@capacitor/privacy-screen` | 2.x | aperçu masqué dans le sélecteur d'applications, captures bloquées sur Android |
| `@capacitor/barcode-scanner` | 3.x | scan du QR de la crèche et d'appairage de la tablette |
| `@aparajita/capacitor-secure-storage` | 8.x | jetons dans le Keychain (iOS) / Keystore (Android), ADR 0024 |
| `@aparajita/capacitor-biometric-auth` | 10.x | déverrouillage par Face ID / empreinte, ADR 0024 |

- Seuls deux plugins ne viennent pas de l'équipe Capacitor (secure storage, biométrie) : MIT, maintenus, suivent les
  versions majeures de Capacitor. S'ils étaient abandonnés, ils se remplacent par un petit plugin local (une centaine
  de lignes Swift / Kotlin chacun) sans toucher au front, qui passe par `web/src/lib/native/`.
- **Mode kiosque** : pas de plugin tiers. Plugin local `KioskMode` dans `mobile/pointage/android` (`startLockTask`,
  démarrage au boot, écran allumé). Sur iPad : Accès guidé, réglé à la main (décision 5 du plan).
- **Caméra** : le kiosque garde `getUserMedia` (photos, vidéos de transmission), autorisé par la vue web native ;
  `@capacitor/camera` n'est ajouté que si un appareil le rend nécessaire.
- **Pas de** Firebase SDK, d'Ionic Appflow, de Capgo ni de service de mise à jour à chaud (ADR 0027), de bibliothèque
  d'interface mobile (Ionic) : l'interface reste Tailwind + shadcn.

**Plateformes minimales** : iOS 15 (Capacitor 8) et Android 8 / API 26 (exigé par le scanner de QR officiel ; Capacitor seul descendrait à l’API 24). Tablette de pointage : Android 10 ou
plus conseillé (Lock Task en propriétaire d'appareil).

**Outillage** : Android Studio / SDK et JDK 21 pour Android ; Xcode sur Mac ou CI macOS pour iOS (décision 4 du plan).
Les tests du front restent Vitest en jsdom : les plugins sont simulés derrière `lib/native`.

## Alternatives écartées

- **PWA seule** : pas de store, notifications iPhone conditionnées à l'écran d'accueil, pas de verrouillage kiosque.
- **React Native / Expo** : Tailwind, shadcn et Radix n'existent pas en natif ; toute l'interface serait à réécrire.
- **Natif Swift + Kotlin** : deux bases de code de plus, pour une équipe d'une personne.
- **Une seule application « Ti'Cagou » avec trois modes** : les stores et les parents s'y perdraient, et l'application
  des parents porterait le code et les permissions du kiosque. Trois applications, trois fiches claires.
- **Capacitor configuré dans `web/`** : un dossier Capacitor ne porte qu'une configuration ; Direction et Pointage ont
  besoin de deux identifiants, deux icônes, deux jeux de permissions.

## Conséquences

- Le dépôt contient du code natif (Gradle, Xcode) ; la CI construit au moins l'Android.
- Chaque version du serveur peut entraîner une version des applications (ADR 0027).
- Le stockage local du kiosque (`kiosk/secureStore`, ADR 0005 et 0013) passe sa clé dans le Keystore sur la tablette
  native ; dans le navigateur, rien ne change.
- Les règles métier ne bougent pas : santé visible partout où l'enfant apparaît, montants en `long`, `structure_id`,
  Pacific/Noumea, interface en français.
