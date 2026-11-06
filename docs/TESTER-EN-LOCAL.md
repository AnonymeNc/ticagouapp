# Tester Ti'Cagou depuis votre ordinateur

Tout ce qu'il faut pour simuler une installation et essayer le logiciel avec chaque profil : directrice, équipe de
la crèche (éducatrice, cuisine, comptabilité), parent. Les commandes se lancent depuis la racine du dépôt et
fonctionnent **telles quelles dans Windows PowerShell 5.1 et dans Git Bash** (on enchaîne avec `;`, jamais `&&`).

| Profil | Où | Comment il se connecte |
|---|---|---|
| Directrice | PC du bureau, http://localhost:1420 | identifiant et mot de passe ; code PIN sur la tablette |
| Comptabilité | PC du bureau | identifiant et mot de passe |
| Éducatrice, cuisine | tablette, http://localhost:1420/kiosk | code PIN à 4 chiffres |
| Parent | application des parents, http://localhost:5180 | e-mail, puis lien ou code à 6 chiffres reçu dans Mailpit |

## 1. Prérequis (une seule fois)

- **Docker Desktop**, démarré : bases de données et tests d'intégration.
- **JDK 21** : `java -version` doit afficher 21.
- **Node 24 et pnpm** : `corepack enable` active pnpm.

## 2. Tout lancer en une commande

```powershell
powershell -ExecutionPolicy Bypass -File scripts\tester-en-local.ps1 -Portail
```

Le script :
- démarre Docker Desktop si besoin, puis les bases (serveur sur 5435, portail sur 5434) et Mailpit ;
- ouvre une fenêtre par programme : serveur (8080), application du bureau (1420), portail (8090), application
  des parents (5180). Le port 8080 est libéré de force : si le Ti'Cagou installé (V1) tourne sur ce PC, Windows demande
  une autorisation pour l'arrêter. Les autres programmes déjà lancés ne sont pas relancés ;
- attend qu'ils répondent, puis affiche les adresses et un **code d'activation** du portail (§ 6).

Sans `-Portail`, seuls la base, le serveur et le bureau démarrent. Le premier lancement compile : comptez quelques
minutes.

**Démonstration toute prête** (rendez-vous commercial, formation), avec le portail et cinq familles connectées ; ajoutez
`-Formule Essentiel` ou `-Formule Gestion` pour montrer une autre offre ([parcours](commercial/demonstrations.md)) :

```powershell
powershell -ExecutionPolicy Bypass -File scripts\tester-en-local.ps1 -Demo
```

Le serveur démarre sur une base à part, `ticagou_demo`. Votre base de développement n'est pas touchée. La base est
remplie par `scripts\demo\donnees-demo.mjs` :
- « Les P'tits Niaoulis », 24 enfants et leurs familles ;
- personnes autorisées, allergies, un PAI ;
- l'équipe avec ses codes PIN ;
- trois mois de présences ;
- les factures des deux mois passés, en partie payées, avec un avoir ;
- trois familles en anomalie, pour une absence maladie sans justificatif ;
- les menus et des demandes d'inscription.

Les fois suivantes, seuls les pointages et transmissions du jour sont rejoués. `-Demo -RemiseAZero` efface la base de
démonstration et la recharge. Les comptes sont ceux du § 8.

Le serveur de développement est lancé avec une **évaluation de 10 ans** (`TICAGOU_EVALUATION_DAYS=3650`) : la base
de développement, installée il y a longtemps, passerait sinon en lecture seule (ADR 0011). Pour essayer la fin de
l'évaluation ou un abonnement échu, voir le § 7, ligne « Abonnement ».

Pour tout arrêter :

```powershell
powershell -ExecutionPolicy Bypass -File scripts\tester-en-local.ps1 -Arreter
```

Lancement à la main (trois terminaux), si vous préférez :

```bash
docker compose up -d postgres
```

```bash
cd server; ./mvnw spring-boot:run
```

```bash
cd web; pnpm install; pnpm dev
```


## 3. Simuler l'installation

### A. Comme au premier jour, sur votre poste (recommandé)

Rejoue l'assistant d'installation sur une base vide. **Cela efface la base de développement** : faites un instantané
avant si vous tenez aux données (§ 9). Arrêtez d'abord le serveur (fenêtre « Serveur », Ctrl+C), puis :

```powershell
powershell -ExecutionPolicy Bypass -File scripts\tester-en-local.ps1 -RemiseAZero -Portail
```

Ouvrez http://localhost:1420 : l'assistant s'affiche, comme pour une nouvelle crèche.
1. **Structure** : nom, ville, RIDET, numéro et date d'agrément, capacité agréée.
2. **Sections** : par exemple Bébés (2 à 18 mois), Moyens (18 à 30 mois), Grands (30 mois et plus), avec leurs places.
3. **Compte de la direction** : prénom, nom, identifiant et mot de passe. Notez-les : ce sont les vôtres pour la suite.
4. **Clé de secours** des sauvegardes : imprimez la fiche (PDF enregistré dans vos Téléchargements), puis recopiez le
   dernier groupe pour confirmer.

Vous arrivez sur le tableau de bord, connectée en directrice. Le logiciel est en **évaluation** : tous les modules sont
ouverts. Pour tester un abonnement, écrivez une ligne dans la table `subscription` de la base de développement.

### B. Comme en production, avec Docker

Pour essayer l'image, Caddy et `creche.sh` sur votre PC, dans Git Bash :

1. `cd deploy`, puis créez `socle.env` : `DOMAINE_BASE=localhost`, `TICAGOU_DONNEES` vers un dossier de test,
   `POSTGRES_PASSWORD`, `TLS_INTERNE=1`, `HTTP_PORT=8180`, `HTTPS_PORT=9443`.
2. `export MSYS_NO_PATHCONV=1`, puis `./creche.sh socle`, `./creche.sh construire essai` et `./creche.sh creer demo`.
3. Ouvrez `https://demo.localhost:9443` : Caddy utilise un certificat interne, que votre navigateur signale.

Avec Docker, la double authentification est active : il faut une application d'authentification.

## 4. Se connecter en directrice

Sur http://localhost:1420, avec le compte créé à l'assistant, ou celui de la base de
démonstration (§ 8).
- **Tableau de bord** : présents, encadrement en direct, alertes, absences signalées par les parents.
- **Paramètres** (réservés à la direction) : structure, sections, encadrement, barème, utilisateurs, tablettes,
  sauvegardes, portail parents.
- **Code PIN de la directrice** : Paramètres › Utilisateurs et rôles › **PIN**. Il sert sur la tablette, pour pointer
  et pour **valider un départ avec une personne non autorisée** (§ 5 D).

## 5. Se connecter en membre de l'équipe

### A. Créer les comptes (directrice)

Paramètres › **Utilisateurs et rôles** › **Ajouter** :

| Rôle | Mot de passe (PC du bureau) | PIN (tablette) | Voit |
|---|---|---|---|
| Éducatrice | facultatif | oui | enfants, présences, pointage, journée, messages |
| Cuisine | facultatif | oui | repas du jour, régimes ; consultation seule sur la tablette |
| Comptabilité | oui | non | facturation, financeurs, barème |

Un PIN fait 4 chiffres et doit être unique dans la structure. Une éducatrice avec mot de passe peut aussi ouvrir le
PC du bureau ; la comptabilité se connecte sur http://localhost:1420 avec son identifiant et voit seulement ses menus.

### B. Appairer le navigateur comme une tablette (une fois par navigateur)

1. Directrice, sur le PC : Paramètres › **Tablettes et abonnement** › **Appairer une tablette** › un nom ›
   **Obtenir le code**. Laissez la fenêtre ouverte (code à 6 chiffres et QR code).
2. Dans **un autre onglet** : http://localhost:1420/kiosk, tapez les 6 chiffres (l'envoi part au 6ᵉ).
3. La tablette affiche « Qui êtes-vous ? ». Sur le PC, cliquez sur **Terminé**.

Code expiré : **Nouveau code** sur la ligne de la tablette. L'appairage est gardé dans le navigateur (clé
`ticagou.device` du stockage local) : une base remise à zéro oblige à réappairer.

Avec un vrai téléphone ou une tablette sur le même Wi-Fi : scannez le QR code. Si rien ne s'ouvre, le pare-feu
bloque le port 8080 (voir § 10).

### C. Ouvrir une session et pointer

1. « Qui êtes-vous ? » : touchez votre nom, puis tapez votre PIN (validation automatique au 4ᵉ chiffre).
2. Grille du jour : toucher un enfant attendu enregistre son **arrivée** (consignes du parent, médicament) ; un enfant
   présent ouvre son **départ** (qui vient le chercher).
3. **Journée** : choisir un ou plusieurs enfants présents, puis Repas, Sieste, Change, Activité, Photo ou Mot.
   Cochez « Partager avec les familles » pour qu'un parent le voie. Une photo n'est partageable que si chaque enfant
   a l'autorisation de photographier (fiche › Autorisations). Hors connexion, tout est gardé sauf les photos.
4. **Pointage personnel** : n'importe qui tape **son** PIN ; la tablette reconnaît l'arrivée ou le départ, et
   l'encadrement se met à jour sur `/presences`.
5. Changer de personne : bouton à votre nom, en haut à droite, **Changer d'utilisateur**.

La cuisine voit la grille en « Consultation seulement ».

### D. Départ avec une personne non autorisée

1. L'enfant doit être présent (sinon **Accueillir un enfant hors planning**). Touchez-le une seconde fois : écran
   « Départ de … ».
2. **Une autre personne** › **Continuer avec une autre personne** : écran rouge « Personne NON autorisée ».
3. Remplissez **d'abord** prénom, nom et lien (et la pièce d'identité vérifiée), puis la directrice tape son PIN.
4. Un mauvais code bloque le départ ; le bon l'enregistre, tracé avec la validation.

## 6. Se connecter en parent

1. **Activer le portail** (directrice) : Paramètres › **Portail parents** › adresse `http://localhost:8090` et le code
   d'activation affiché par le script. Sinon, créez-en un :

   ```powershell
   Invoke-RestMethod -Method Post -Uri http://localhost:8090/admin/v1/activation-codes -Headers @{Authorization='Bearer dev'} -ContentType 'application/json' -Body '{"label":"Test"}'
   ```

2. **Inviter un parent** : fiche d'un enfant › **Journée et portail** › **Inviter au portail** sur la ligne d'un
   responsable. Il lui faut une adresse e-mail (fiche › Famille), par exemple `claire.garnier@example.nc` : en local,
   aucun e-mail ne part vraiment.
3. Partagez quelque chose : une transmission sur la tablette (§ 5 C), une facture validée, un message (menu
   **Messages**), une autorisation à signer (fiche › Journée et portail). La synchronisation passe chaque minute ;
   **Synchroniser maintenant** dans Paramètres › Portail parents pour ne pas attendre.
4. **Connexion du parent** : http://localhost:5180, saisissez l'adresse invitée, **Recevoir mon lien de connexion**.
   Ouvrez Mailpit (http://localhost:8025) : cliquez sur le lien, ou recopiez le code à 6 chiffres.
5. Parcours parent : la journée de l'enfant, les messages, **Signaler une absence**, les factures, les documents à
   signer (réponse, tracé au doigt, certification).
6. Retour côté crèche (après une synchronisation) : absence à confirmer sur le tableau de bord, message dans
   **Messages**, signature à enregistrer dans Paramètres › Portail parents.

Le bandeau « Prévenez avant 09:00 : le repas du jour ne vous sera pas facturé » n'apparaît que si l'heure est réglée
dans Paramètres › Portail parents (« Repas non facturé si l'absence est signalée avant »).

Pour voir l'application comme sur un téléphone : outils de développement du navigateur (F12), mode appareil mobile.

## 7. Parcours de test par fonctionnalité

| Fonctionnalité | Où | À vérifier |
|---|---|---|
| Installation | `/setup` (base vide) | Structure, sections, compte direction, clé de secours ; refusée une 2ᵉ fois |
| Connexion | `/login` | Mot de passe, verrouillage après plusieurs échecs, journal d'audit |
| Tableau de bord | `/` | Présents, encadrement, alertes, absences signalées |
| Enfants et familles | `/enfants`, `/enfants/:id` | Allergies, PAI, documents chiffrés, personnes autorisées |
| Inscriptions | `/inscriptions`, `/inscriptions/capacite` | Étapes, capacité, passage en contrat |
| Contrats | `/enfants/:id/contrat/nouveau` | Barème, mensualisé ou au réel, PDF, avenant |
| Pointage tablette | `/kiosk` | § 5 ; santé visible sur les tuiles |
| Hors connexion | `/kiosk`, puis arrêter le serveur | Pointer, relancer : la file d'attente se vide |
| Encadrement en direct | `/presences` | Ratio mis à jour quand on pointe sur la tablette |
| Transmissions | `/kiosk` › Journée, fiche › Journée et portail | Saisie, partage, photos autorisées seulement |
| Repas | `/repas`, `/repas/menus`, `/repas/recapitulatif` | Comptage, régimes, menus |
| Facturation | `/facturation` | Aides déduites, facture validée immuable, avoir, numérotation continue |
| Encaissements | `/facturation/encaissements` | Paiement partiel, reste dû |
| Personnel | `/personnel` | Fiches, planning, heures |
| Sauvegardes | Paramètres › Sauvegardes | Sauvegarde manuelle, test de restauration, alerte 48 h |
| Portail parents | Paramètres › Portail parents, http://localhost:5180 | § 6 ; désactivation qui efface tout |
| Statistiques | `/statistiques` | Occupation, heures réalisées et contractualisées, aides ; bilan PDF et Excel |
| Export comptable | `/facturation/comptabilite`, Paramètres › Comptabilité | Contrôle d'équilibre, CSV, FEC ; compte changé à une date |
| Supervision | Paramètres › Supervision (avec `-Portail`) | Code créé par `POST http://localhost:8090/admin/v1/supervision-codes` (jeton `dev`) ; alertes dans Mailpit |
| Abonnement | Paramètres › Tablettes et abonnement | PC comptés et retirés. Lecture seule : arrêtez le serveur, relancez-le avec `$env:TICAGOU_EVALUATION_DAYS='0'` sur une base sans clé |

Astuce : deux fenêtres côte à côte, le bureau sur `:1420` et la tablette sur `:1420/kiosk`, pour voir le temps réel.
La session de la tablette n'est jamais conservée : un rechargement redemande le PIN.

## 8. Comptes de la base de démonstration

Les mêmes comptes valent pour deux bases :
- la base de développement, saisie à la main pendant le développement, qu'une remise à zéro efface ;
- la base de démonstration (`-Demo`), créée par `scripts\demo\donnees-demo.mjs`.

| Qui | Rôle | PC du bureau | PIN tablette |
|---|---|---|---|
| Nathalie Kaloi | Direction | `nathalie.kaloi` / `Niaouli-2026!` | `2580` |
| Sandrine W. | Éducatrice | — | `4831` |
| Maëva Tiaou | Éducatrice | — | `2468` |
| Léa Kaouma | Éducatrice | — | `1357` |
| Mereana Tehei | Éducatrice | — | `3691` |
| Paulo Taufana | Cuisine | — | `7419` |

## 9. Tests automatisés

Docker Desktop doit tourner : les tests d'intégration utilisent leur propre base, **pas** celle de développement.

```bash
cd server; ./mvnw verify
```

```bash
cd parents-portal/server; ./mvnw verify
```

```bash
cd web; pnpm test; pnpm typecheck
```

```bash
cd parents-portal/web; pnpm test; pnpm typecheck
```

Un seul test, guillemets compris pour PowerShell : `./mvnw verify "-Dtest=InvoiceCalculatorTest"` (unitaire) ou
`./mvnw verify "-Dit.test=PortalIT"` (intégration).

## 10. Instantané, restauration, remise à zéro

**Instantané** (avant toute remise à zéro) :

```bash
docker exec ticagou-web-postgres pg_dump -U ticagou -d ticagou -Fc -f demo.dump; docker cp ticagou-web-postgres:demo.dump demo.dump; docker exec ticagou-web-postgres rm demo.dump
```

```bash
cp -r server/data demo-data
```

Les documents chiffrés (`server/data`) vont avec le dump : leur clé est en base.

**Remise à zéro** : `scripts\tester-en-local.ps1 -RemiseAZero` (§ 3 A), ou à la main, serveur arrêté :
`docker compose down -v; docker compose up -d --wait postgres`, puis supprimer `server/data`.

**Restauration**, serveur arrêté :

```bash
docker compose down -v; docker compose up -d --wait postgres
```

```bash
docker cp demo.dump ticagou-web-postgres:demo.dump; docker exec ticagou-web-postgres pg_restore -U ticagou -d ticagou --no-owner demo.dump; docker exec ticagou-web-postgres rm demo.dump
```

Puis remplacer `server/data` par `demo-data`. Ne commitez ni `demo.dump` ni `demo-data/`.

**Explorer la base** : `docker exec -it ticagou-web-postgres psql -U ticagou -d ticagou` (préfixer par `winpty` dans
Git Bash), ou DBeaver sur `localhost:5435`.

## 11. Pannes fréquentes

- *Port 5435, 8080, 1420, 8090 ou 5180 occupé* : `netstat -ano | findstr :8080` donne le PID, puis
  `taskkill /PID <pid> /F` (PowerShell) ou `taskkill //PID <pid> //F` (Git Bash).
- *Le script garde un serveur déjà lancé* : il ne relance pas un programme dont le port répond. Arrêtez l'ancien pour
  charger le dernier code.
- *`Le jeton « && » n'est pas un séparateur d'instruction valide`* : PowerShell 5.1, remplacez `&&` par `;`.
- *Flyway « checksum mismatch »* : une migration appliquée a été modifiée ; en développement, remise à zéro.
- *Tests d'intégration « Skipped »* : Docker Desktop n'est pas lancé.
- *`/kiosk` affiche « Appairer cette tablette »* : navigateur non appairé, ou base remise à zéro (§ 5 B).
- *Le QR code ne s'ouvre pas sur le téléphone* : en PowerShell administrateur,
  `Get-NetFirewallRule -DisplayName "Ti'Cagou" | Set-NetFirewallRule -Profile Any -RemoteAddress LocalSubnet`
  (installation réelle) ; en développement, autorisez Java sur le réseau privé quand Windows le demande.
- *Le parent ne reçoit rien* : regardez Mailpit (http://localhost:8025) ; l'adresse doit être celle d'un responsable
  invité, et le portail activé.
- *« Synchronisation : portail injoignable »* : le portail (fenêtre « Portail ») est-il lancé sur 8090 ?
