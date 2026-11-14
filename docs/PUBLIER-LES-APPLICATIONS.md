# Publier les applications mobiles, pas à pas

Trois applications : **Ti'Cagou Direction** (iPhone, Android), **Ti'Cagou Pointage** (tablettes Android seulement),
**Ti'Cagou Parents** (iPhone, Android). Ce guide va de zéro à la première version dans les stores. Suivez les étapes
dans l'ordre ; chaque étape dit **ce que vous faites** et **ce qui est déjà fait**.

| | Qui | Durée |
|---|---|---|
| A. Préalables (société, D-U-N-S, site) | vous | 1 à 3 semaines (D-U-N-S) |
| B. Compte Google Play | vous (paiement, identité) | 1 h + vérification Google (quelques jours) |
| C. Compte Apple | vous (paiement, identité) | 1 h + vérification Apple (quelques jours) |
| D. Firebase (notifications Android) | vous, 15 min | — |
| E. Clés sur le serveur | vous, 10 min | — |
| F. Première version | GitHub Actions + vous | 1 h, puis relecture Apple (1 à 3 jours) |
| Clé de signature Android, secrets GitHub | **déjà fait** (`scripts/secrets-mobile.ps1`, 15/11/2026) | — |

Ce que Claude ne peut pas faire à votre place : payer, saisir une carte, un mot de passe ou une pièce d'identité, et
accepter les contrats des stores. Tout le reste est scripté ou décrit ici au clic près.

---

## A. Préalables

1. **La société éditrice** existe (RIDET) : les comptes sont ouverts à son nom, pas au vôtre.
2. **Numéro D-U-N-S** de la société : gratuit. Apple le vérifie et Google le demande pour un compte d'organisation.
   - Cherchez d'abord si la société en a déjà un : https://developer.apple.com/enroll/duns-lookup/
   - Sinon, faites la demande depuis la même page (formulaire de Dun & Bradstreet). Comptez 1 à 3 semaines ; gardez
     l'e-mail de confirmation.
3. **Une adresse e-mail de l'éditeur** (par exemple `applications@ticagou.nc`) et **un téléphone** joignable : Apple
   appelle parfois pour vérifier.
4. **Un site web** à votre nom de domaine avec :
   - la [politique de confidentialité](commercial/stores/politique-confidentialite.md), publiée à une adresse stable
     (par exemple `https://ticagou.nc/confidentialite`) ;
   - une page de contact (adresse de support exigée par les stores).
5. **Une crèche de démonstration hébergée** pour les relecteurs d'Apple et Google, qui ne peuvent ni scanner un QR ni
   lire un code de double authentification : sur le serveur, `./creche.sh demo demo-stores`. Une démonstration n'exige
   pas la double authentification (données fictives). Adresse : `https://demo-stores.<DOMAINE_BASE>` ; compte
   `nathalie.kaloi` / `Niaouli-2026!`. Ils servent aux étapes B6 et C10.

## B. Google Play (Direction, Pointage, Parents)

1. Ouvrez https://play.google.com/console/signup avec un compte Google **de la société** (pas votre compte
   personnel).
2. Choisissez **Organisation**. Renseignez la société, le D-U-N-S, l'adresse, le site, l'e-mail et le téléphone de
   contact. Payez les **25 $** (une seule fois). Google vérifie l'identité : quelques jours.
   - Un compte d'organisation n'a pas l'obligation de « test fermé de 14 jours avec 12 testeurs » imposée aux comptes
     personnels récents.
3. Une fois le compte validé : **Créer une application**, trois fois :

   | Nom | Langue par défaut | Type | Prix |
   |---|---|---|---|
   | Ti'Cagou Direction | Français (France) – fr-FR | Application | Gratuite |
   | Ti'Cagou Pointage | Français (France) – fr-FR | Application | Gratuite |
   | Ti'Cagou Parents | Français (France) – fr-FR | Application | Gratuite |

   Cochez les deux déclarations (règles du programme, lois d'exportation des États-Unis).
4. **Signature d'application par Google Play** : laissez l'option par défaut (Google garde la clé de signature
   finale). La clé déjà créée dans votre coffre (`Documents\Ticagou-coffre\ticagou-android-upload.jks`) est votre
   **clé d'importation** : elle signe les fichiers que vous envoyez. Perdue, elle se réinitialise auprès du support
   Google ; elle reste à garder précieusement (coffre + clé USB).
5. **Fiche principale** (Croissance › Fiche principale du Store), pour chaque application, avec les textes de
   [fiches-stores.md](commercial/stores/fiches-stores.md) :
   - icône 512 × 512 : `mobile/<app>/www/icons/icon-512.png` ;
   - image de présentation 1024 × 500 : `docs/commercial/captures/stores/<app>/presentation-1024x500.png` ;
   - captures : `docs/commercial/captures/stores/<app>/*-store-android.png` (téléphone) et
     `*-store-tablette-android.png` / `*-store-ipad.png` recadrées (tablette 7 et 10 pouces).
6. **Contenu de l'application** (Règles › Contenu de l'application), pour chaque application :

   | Rubrique | Réponse |
   |---|---|
   | Règles de confidentialité | l'adresse publiée en A4 |
   | Accès aux applications | « Tout ou partie des fonctionnalités est soumis à des restrictions » ; instructions : adresse de la crèche de démonstration, identifiant et mot de passe (A5) |
   | Annonces | Non |
   | Classification du contenu | questionnaire : catégorie « Utilitaire, productivité… », aucune violence ni contenu sensible |
   | Public cible | 18 ans et plus (l'application s'adresse aux professionnels et aux parents, pas aux enfants) |
   | Sécurité des données | réponses de [declarations-confidentialite.md](commercial/stores/declarations-confidentialite.md) |
   | Applications gouvernementales, fonctionnalités financières | Non |
   | Applications de santé | Non : la crèche gère des informations d'accueil, l'application n'est pas un service de santé |

7. **Pays** (Production › Pays et régions) : Nouvelle-Calédonie, France, puis les territoires de votre clientèle.
8. **Testeurs internes** (Test › Test interne › Testeurs) : créez une liste avec vos adresses et celles de la crèche
   pilote. Le premier fichier s'envoie à l'étape F.

## C. Apple (Direction, Parents)

1. Ouvrez https://developer.apple.com/programs/enroll/ avec un identifiant Apple **de la société** (double
   authentification activée). Choisissez **Organisation** et donnez le D-U-N-S, le site web et votre rôle (vous devez
   pouvoir engager la société). Payez les **99 $ par an**. Apple vérifie, parfois par téléphone : quelques jours.
2. **Team ID** : https://developer.apple.com/account › Adhésion › « ID d'équipe » (10 caractères). Notez-le.
3. **Identifiants des applications** : Certificates, Identifiers & Profiles › Identifiers › **+** › App IDs › App :

   | Description | Bundle ID (Explicit) | Capacités à cocher |
   |---|---|---|
   | Ti'Cagou Direction | `nc.ticagou.direction` | Push Notifications |
   | Ti'Cagou Parents | `nc.ticagou.parents` | Push Notifications |

4. **Clé des notifications (APNs)** : Keys › **+** › nom « Ti'Cagou APNs » › cochez **Apple Push Notifications service
   (APNs)** › Continue › Register › **Download**. Le fichier `AuthKey_XXXXXXXXXX.p8` ne se télécharge **qu'une fois** :
   mettez-le dans `Documents\Ticagou-coffre`. Notez son Key ID (les 10 caractères du nom). Elle sert à l'étape E.
5. **Applications dans App Store Connect** : https://appstoreconnect.apple.com › Apps › **+** › Nouvelle app :
   plateforme iOS, nom (« Ti'Cagou Direction », puis « Ti'Cagou Parents »), langue principale Français, le Bundle ID de
   l'étape 3, SKU `ticagou-direction` / `ticagou-parents`, accès complet.
6. **Clé d'API App Store Connect** (pour que GitHub envoie les versions) : Utilisateurs et accès › Intégrations › App
   Store Connect API › Clés d'équipe › **+** › nom « GitHub Actions », accès **Admin** › Générer. Téléchargez la clé
   (`AuthKey_YYYYYYYYYY.p8`, une seule fois, au coffre). Notez l'**ID de la clé** et l'**ID de l'émetteur** (en haut de
   la page).
7. Enregistrez tout dans GitHub d'une commande (depuis la racine du dépôt, PowerShell) :

   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\secrets-mobile.ps1 -AppleTeamId <TEAM_ID> -AppStoreKeyId <ID_DE_LA_CLE> -AppStoreIssuerId <ID_EMETTEUR> -AppStoreKeyFile "$HOME\Documents\Ticagou-coffre\AuthKey_YYYYYYYYYY.p8"
   ```

8. **Fiche** de chaque application (onglet Distribution) : textes de [fiches-stores.md](commercial/stores/fiches-stores.md),
   URL de confidentialité (A4), URL de support, captures `*-store-iphone.png` (6,9 pouces) et `*-store-ipad.png`
   (13 pouces), catégorie, âge 4+.
9. **Confidentialité de l'app** : réponses de [declarations-confidentialite.md](commercial/stores/declarations-confidentialite.md).
10. **Informations de relecture** : identifiant et mot de passe du compte de démonstration (A5), avec cette note :
    « Choisir “Autre adresse”, saisir https://demo-stores.ticagou.nc, puis se connecter. Le QR code et le code de la crèche
    sont remis à la mise en service ; l'application n'est utile qu'aux crèches abonnées. »

## D. Firebase (notifications Android)

1. https://console.firebase.google.com avec le compte Google de la société › **Créer un projet** « Ti'Cagou »
   (Google Analytics : désactivé).
2. Dans le projet : **Ajouter une application** › Android, trois fois : `nc.ticagou.direction`,
   `nc.ticagou.pointage`, `nc.ticagou.parents` (surnom libre, pas de certificat SHA-1 nécessaire). À chaque fois,
   téléchargez `google-services.json` et rangez-le dans `Documents\Ticagou-coffre\<app>\`.
3. Paramètres du projet › **Comptes de service** › **Générer une nouvelle clé privée** › fichier JSON au coffre
   (`Documents\Ticagou-coffre\firebase-compte-de-service.json`). Il sert à l'étape E. L'API « Firebase Cloud Messaging
   (V1) » est activée d'office sur un projet neuf (Paramètres › Cloud Messaging).
4. Enregistrez les trois fichiers dans GitHub :

   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\secrets-mobile.ps1 -GoogleServicesDirection "$HOME\Documents\Ticagou-coffre\direction\google-services.json" -GoogleServicesPointage "$HOME\Documents\Ticagou-coffre\pointage\google-services.json" -GoogleServicesParents "$HOME\Documents\Ticagou-coffre\parents\google-services.json"
   ```

## E. Les clés de notification sur le serveur

Sur votre PC, transformez les deux fichiers en une ligne (PowerShell) :

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\Documents\Ticagou-coffre\AuthKey_XXXXXXXXXX.p8")) | Set-Clipboard
```

Sur le serveur, `nano /opt/ticagou/ticagou-web/deploy/socle.env`, collez sur la ligne `APNS_KEY=`, puis :

```bash
APNS_KEY_ID=XXXXXXXXXX
APNS_TEAM_ID=<TEAM_ID>
APNS_PRODUCTION=true
```

Recommencez la commande PowerShell avec `firebase-compte-de-service.json` et collez sur `FCM_SERVICE_ACCOUNT=`. Puis :

```bash
cd /opt/ticagou/ticagou-web/deploy && ./creche.sh mettre-a-jour "$(cat .version)" --toutes && ./creche.sh socle --portail
```

Copiez aussi ces lignes dans la note « Ti'Cagou – socle » de votre coffre.

## F. Première version

1. GitHub › Actions › **Publication mobile** › *Run workflow* : numéro de build `1`, plateformes « android et ios ».
   Comptez 20 à 40 minutes.
2. **Android** : en bas de la page du run, téléchargez l'artefact `applications-android-1` (zip) et décompressez-le.
   Dans la Play Console, pour chaque application : Test › Test interne › **Créer une release** › déposez le fichier
   `.aab` correspondant (`direction`, `pointage`, `parents`) › nom de version « 1.0.0 (1) » › Enregistrer › Examiner ›
   Lancer le déploiement. Les testeurs reçoivent un lien d'installation.
3. **iOS** : les deux applications arrivent seules dans App Store Connect › TestFlight (traitement : 15 à 30 minutes).
   Ajoutez-vous comme testeur interne ; installez l'app **TestFlight** sur votre iPhone.
4. **Essais sur de vrais appareils** (liste de [securite-applications-mobiles.md](securite-applications-mobiles.md) § 7) :
   Face ID, notification de départ à valider, mode kiosque sur la tablette, scan des QR, suppression du compte parent.
5. **Publication** : Play Console › Production › Créer une release (mêmes `.aab`, ou « Promouvoir » depuis le test
   interne) › Envoyer pour examen. App Store Connect › la version › choisir le build TestFlight › **Soumettre pour
   vérification**. Apple répond en 1 à 3 jours ; Google en quelques heures à quelques jours.
6. Les versions suivantes : numéro de build + 1 à chaque fois ([EXPLOITATION.md § 7 bis](EXPLOITATION.md#7-bis-les-applications-mobiles)).

## G. Faire une démonstration sur un téléphone

| Situation | Comment |
|---|---|
| Tout de suite, sans store | Navigateur du téléphone sur la crèche de démonstration hébergée ; l'application des parents s'installe par « Ajouter à l'écran d'accueil ». |
| Présentation projetée depuis le PC | `scripts\tester-en-local.ps1 -Demo -Mobile`, puis F12 › mode téléphone (http://localhost:1431 Direction, http://localhost:5180 Parents). |
| Vraies applications Android | APK de test de la CI (Actions › Tests › dernier run › artefact `applications-android`), installés sur un téléphone ou une tablette de démonstration (autoriser les sources inconnues), connectés à la crèche de démonstration hébergée. |
| Vraies applications iPhone | TestFlight (étape F3), puis l'App Store une fois publiées. |

Déroulé de la démonstration : [demonstrations.md](commercial/demonstrations.md), section « Démonstration mobile ».

## Récapitulatif des secrets

| Secret | Où | Créé par |
|---|---|---|
| Clé Android d'importation (`.jks`, mot de passe, alias) | coffre + GitHub (`ANDROID_*`) | script, **fait** |
| `GOOGLE_SERVICES_DIRECTION/POINTAGE/PARENTS` | GitHub | script, à l'étape D |
| `APPLE_TEAM_ID`, `APP_STORE_CONNECT_KEY_ID`, `APP_STORE_CONNECT_ISSUER_ID`, `APP_STORE_CONNECT_KEY_BASE64` | GitHub | script, à l'étape C7 |
| `APNS_KEY`, `APNS_KEY_ID`, `APNS_TEAM_ID`, `FCM_SERVICE_ACCOUNT` | `socle.env` du serveur | vous, étape E |
| Mots de passe des comptes Apple, Google, Firebase | votre gestionnaire de mots de passe | vous |

Gardez le dossier `Documents\Ticagou-coffre` dans votre gestionnaire de mots de passe **et** sur une clé USB rangée
hors du bureau. Il n'est jamais envoyé dans le dépôt (`.gitignore` refuse les `.jks`, `.p8` et `google-services.json`).
