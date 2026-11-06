# Ti'Cagou — logiciel de gestion pour crèches et garderies de Nouvelle-Calédonie

Nom de travail : **Ti'Cagou** (« petit cagou »), à changer librement.

Application web hébergée, une instance par crèche ([ADR 0013](docs/adr/0013-web-heberge-instance-par-creche.md)) : enfants
& familles, inscriptions, contrats, pointage tablette (hors connexion compris), taux d'encadrement temps réel, repas,
facturation XPF avec aides, personnel, sauvegardes. La V1 installée dans la crèche est archivée dans
`ticagou-creche-v1-client-lourd`.

## Arborescence

| Dossier / fichier | Rôle |
|---|---|
| `server/` | API REST Spring Boot 3 (Java 21, PostgreSQL 17, Flyway) — `/api/v1` |
| `web/` | React 19 : bureau dans le navigateur et route `/kiosk` (tablette d'accueil) |
| `outils/publicite/` | Captures de la démonstration, prospectus 4:3 et maquette des offres |
| `deploy/` | Image Docker, socle et `creche.sh` : créer et exploiter les crèches — guide : [docs/EXPLOITATION.md](docs/EXPLOITATION.md) |
| `parents-portal/` | Portail parents hébergé, optionnel : service Spring Boot (`server/`), application mobile (`web/`), contrat de synchronisation (`contract/`) — [ADR 0010](docs/adr/0010-portail-parents-flux-de-donnees.md) |
| `scripts/tester-en-local.ps1` | Lance toute la démo locale en une commande — guide : [docs/TESTER-EN-LOCAL.md](docs/TESTER-EN-LOCAL.md) |
| `docs/design/` | Maquettes Claude Design : **référence visuelle** |
| `docs/adr/` | Décisions d'architecture : [0001 stack](docs/adr/0001-stack.md), [0002 authentification](docs/adr/0002-authentification.md), [0003 documents chiffrés](docs/adr/0003-documents-chiffres.md), [0004 PDF des contrats](docs/adr/0004-pdf-contrats.md), [0005 tablettes hors connexion](docs/adr/0005-tablettes-hors-connexion.md), [0006 export Excel](docs/adr/0006-export-excel.md), [0013 web hébergé](docs/adr/0013-web-heberge-instance-par-creche.md) |
| `CLAUDE.md` | Règles métier et conventions (lues par Claude Code) |
| `01-concept.md`, `02-techno.md` | Cadrage produit et technique |

## Prérequis

- JDK 21 (Maven est fourni par le wrapper `server/mvnw`)
- Node 22+ et pnpm 10 (`npm i -g pnpm`)
- Docker Desktop pour la base de dev et les tests d'intégration

## Démarrer en développement

> Guide pas à pas pour tester chaque fonctionnalité, lancer les tests, sauvegarder ou remettre à zéro la base :
> [docs/TESTER-EN-LOCAL.md](docs/TESTER-EN-LOCAL.md).

Commandes pour PowerShell (terminal Windows par défaut). Windows PowerShell 5.1 ne connaît pas `&&` :
on enchaîne avec `;`. Le wrapper Maven se lance avec `.\mvnw.cmd` (`./mvnw` sous Git Bash, macOS ou Linux).

```bash
docker compose up -d postgres
```

```powershell
cd server; .\mvnw.cmd spring-boot:run
```

L'API écoute sur http://localhost:8080 — santé : `GET /api/v1/health`, documentation : http://localhost:8080/swagger-ui.html.

```powershell
cd web; pnpm install; pnpm dev
```

Ouvrez http://localhost:1420.

Kiosque tablette : http://localhost:1420/kiosk. La tablette s'appaire d'abord avec le code à 6 chiffres affiché dans
Paramètres › Tablettes (direction). Hors connexion, les pointages restent dans la file chiffrée de la tablette et
partent au retour du réseau ([ADR 0005](docs/adr/0005-tablettes-hors-connexion.md)).
En dev, Vite relaie `/api` vers `http://localhost:8080` (surcharge : variable `TICAGOU_API_URL`).

### Premier lancement et connexion

Au premier démarrage, le navigateur ouvre l'assistant `/setup` (structure, agrément, sections, compte de direction).
Il n'est accepté qu'une fois : en production avec le jeton du lien de démarrage, en développement depuis ce poste. Ensuite :

- Bureau : identifiant + mot de passe, plus un code d'application en production (direction, comptabilité) ; la direction crée les comptes de l'équipe (`/api/v1/users`).
- Tablette (`/kiosk`) appairée : choix de la personne puis code PIN à 4 chiffres ; le personnel pointe son arrivée
  et son départ avec son seul code (unique dans la structure).

Détails (JWT, rafraîchissement tournant, Argon2id, verrouillage, journal d'audit) : [ADR 0002](docs/adr/0002-authentification.md).

Base de dev : la vérification de la phase 1 y a créé « Les P’tits Niaoulis » avec le compte de direction
`nathalie.kaloi` / `Niaouli-2026!` et l'éducatrice Sandrine W. (PIN `4831`), puis la phase 2 trois enfants (Noa Wahéo avec allergies, PAI et documents ; Kaïna ; Léo), et la phase 3
un barème au volume horaire, un contrat signé par enfant (avec un avenant en brouillon pour Noa) et dix demandes
d'inscription réparties sur toutes les étapes. La phase 4 ajoute le code personnel de la directrice (PIN `2580`,
pour valider un départ hors liste), l'équipe Maëva Tiaou (`2468`), Léa Kaouma (`1357`), Mereana Tehei (`3691`) et
Paulo Taufana, cuisine (`7419`), une tablette de démonstration et des pointages. La phase 5 ajoute les menus des
semaines du 26/10 et du 02/11/2026 (avec variantes végétariennes), un régime végétarien pour Léo et une texture mixée
pour Kaïna. La phase 6 ajoute trois financeurs déduits des factures (Province Sud 20 % plafonné à 30 000 F, CAFAT
12 000 F, employeur Lagon Services 10 000 F) et une subvention de la Ville de Dumbéa, les aides accordées aux trois
enfants, deux absences d'octobre (dont une maladie sans certificat), un départ après la fermeture pour Kaïna et un
départ non pointé pour Léo, puis la facture FA-2026-0001 d'octobre (Kaïna) avec un virement partiel de 20 000 F.
La phase 7 complète les fiches du personnel (contrats, diplômes ; Sandrine en CDD de remplacement jusqu'au
27/11/2026, rémunérée à l'heure ; Léa à temps partiel 30 h), des qualifications (SST de Maëva qui expire le
14/11/2026 avec un recyclage prévu le 09/11, HACCP de Paulo expiré), une semaine type par personne depuis le
01/09/2026, des congés pour Léa et les pointages d'octobre de Maëva (le 12/10 jusqu'à 17:00).
Pour repartir de zéro et revoir
l'assistant (supprimez aussi le dossier `server/data`, qui contient les documents chiffrés avec une clé conservée en base) :

```bash
docker compose down -v
```

```bash
docker compose up -d postgres
```

### Variables utiles

| Variable | Défaut | Rôle |
|---|---|---|
| `TICAGOU_DB_URL` | `jdbc:postgresql://localhost:5435/ticagou` | URL JDBC |
| `TICAGOU_DB_USER` / `TICAGOU_DB_PASSWORD` | `ticagou` / `ticagou` | Identifiants base (dev uniquement) |
| `TICAGOU_PORT` | `8080` | Port HTTP du serveur |
| `TICAGOU_JWT_SECRET` | *(vide)* | Clé de signature JWT en Base64 (≥ 32 octets) ; vide = générée et conservée en base |
| `TICAGOU_STORAGE_DIR` | `data/documents` | Documents et photos chiffrés (AES-256-GCM), relatif au dossier de lancement du serveur |
| `TICAGOU_STORAGE_KEY` | *(vide)* | Clé des documents en Base64 (32 octets) ; vide = générée et conservée en base — [ADR 0003](docs/adr/0003-documents-chiffres.md) |
| `TICAGOU_WEB_ROOT` | *(vide)* | Build du front ; vide = `web/dist` du dépôt |
| `TICAGOU_BACKUP_DIR` | `data/backups` | Archives des sauvegardes |
| `TICAGOU_BACKUP_DAILYTIME` | `02:00` | Heure de la sauvegarde de la nuit |
| `TICAGOU_BACKUP_RECOVERYKEY` | *(vide)* | Clé de secours, gardée hors de la base ; vide = générée en base (développement) |
| `TICAGOU_BACKUP_PGBIN` | *(vide)* | Dossier de `pg_dump` / `pg_restore` ; vide = PATH — [ADR 0007](docs/adr/0007-sauvegardes-chiffrees.md) |
| `TICAGOU_BACKUP_SKIPPREMIGRATION` | `false` | Démarrer sans la sauvegarde avant migration (dépannage seulement) |
| `TICAGOU_SETUP_TOKEN` | *(vide)* | Jeton de démarrage de l'assistant ; vide = depuis ce poste seulement |
| `TICAGOU_MFA_REQUIRED` | `false` | Double authentification obligatoire ; `true` dans l'image |
| `TICAGOU_FORWARD_HEADERS` | `none` | `native` derrière le proxy HTTPS (jamais sans proxy) |
| `TICAGOU_API_DOCS` | `true` | Swagger UI et `/v3/api-docs` ; `false` dans l'image |

## Tests

```powershell
cd server; .\mvnw.cmd verify
```

`verify` exécute les tests unitaires puis les tests d'intégration `*IT` (Testcontainers, PostgreSQL 17) ;
ces derniers sont ignorés si Docker n'est pas démarré.

```powershell
cd desktop; pnpm test
```

```powershell
cd desktop; pnpm typecheck
```

## Facturation

- **Facturer le mois** (`/facturation`) : prévisualisation de toutes les familles, anomalies (départ non pointé,
  contrat absent ou expiré, justificatif manquant, payeur manquant), puis génération en lot dans une seule
  transaction. Numérotation continue par année civile, sans trou : `FA-AAAA-NNNN` (factures), `AV-AAAA-NNNN`
  (avoirs), `DF-AAAA-NNNN` (demandes aux financeurs), `R-AAAA-NNNN` (reçus d'espèces).
- **Mensualisé ou au réel** (contrats à planning fixe), choisi à la signature du contrat ou par avenant ; le mode
  proposé par défaut se règle dans Paramètres › Barème et facturation. Exemple : 9 h le mardi à 650 F de l'heure.
  - Mensualisé : 9 h × 52 semaines ÷ 12 = 39 h par mois, soit **25 350 F chaque mois** ;
  - au réel : les mardis du mois, 4 × 9 h = **23 400 F** ou 5 × 9 h = **29 250 F** selon le mois.
  La ligne de la facture explique le calcul. Les jours fériés et fermetures ne sont pas encore retirés au réel
  (calendrier des fermetures à venir).
- Une facture émise ne change plus (trigger en base) : on la corrige par un avoir ; un avoir total permet de
  refacturer le mois. L'état de paiement se déduit du lettrage et n'est jamais stocké.
- Règles paramétrables et datées dans Paramètres › Barème et facturation (heures complémentaires, départs après la
  fermeture, carence, préavis, échéance, arrondi du total), motifs d'absence et taux de TGC (exonérés par défaut,
  **à faire confirmer par l'expert-comptable**). Financeurs dans Paramètres › Financeurs et aides.
- Aucun e-mail n'est encore envoyé : factures, relances, demandes, reçus et attestations s'impriment en PDF, et l'envoi
  est noté à la main.
- Tous les arrondis passent par `billing/Xpf` ; le calcul pur est dans `billing/InvoiceCalculator` (tests unitaires).

## Personnel

- **Équipe et fiches** (`/personnel`) : une fiche par compte (identité, contrat, qualifications et formations avec
  justificatifs chiffrés). Une qualification qui expire déclenche une alerte 30 jours avant (réglable dans
  Paramètres › Utilisateurs et rôles), aussi sur le tableau de bord. Chaque consultation de fiche est tracée.
- **Planning** : semaine type datée par personne, puis exceptions d'un jour (horaire, section, repos) et absences
  (congés, maladie, formation, récupération). L'encadrement prévisionnel compare, par créneau de 30 minutes, les
  enfants attendus selon leur contrat au personnel planifié qui compte dans l'encadrement (mêmes ratios datés que
  le temps réel) ; les créneaux en sous-effectif sont en rouge.
- **Feuilles d'heures** : prévu, pointé (pause déduite), absences et heures au-delà du contrat, par semaine
  rattachée au mois de son dimanche. La direction valide le mois (il se fige, le planning du mois ne change plus) ;
  l'export CSV ou Excel pour la paie porte sur un mois validé.
- Calculs purs et testés : `staff/Shifts`, `staff/ForecastCalculator`, `staff/TimesheetCalculator`.

## Tableau de bord et Paramètres

- **Tableau de bord** (`/`) : actions rapides, alertes par gravité (encadrement insuffisant avec la prochaine
  arrivée planifiée, allergies et PAI des présents, qualifications, impayés, pointages à vérifier, licence),
  encadrement en temps réel, présences du jour (absences signalées, départs prévus avant la fermeture), repas et
  indicateurs du mois calculés par le serveur (`dashboard/Indicators`). La comptabilité ne voit que les indicateurs
  et les impayés ; l'éducatrice ne voit pas les montants.
- **Paramètres** (`/parametres`), par groupes comme la maquette : structure (coordonnées, horaires, banque avec IBAN
  contrôlé et RIB imprimé sur les factures si coché), sections (trous et chevauchements signalés, passages à
  valider), encadrement, critères, repas, barème, financeurs, utilisateurs (avec la matrice des droits, en lecture),
  sauvegardes, tablettes et abonnement. Les rubriques suivent la formule ([ADR 0015](docs/adr/0015-formules-et-modules.md)).
- **Sauvegardes** ([ADR 0007](docs/adr/0007-sauvegardes-chiffrees.md)) : chaque nuit ou à la demande, `pg_dump` et documents
  dans une archive AES-256-GCM ; rotation 7 jours, 4 semaines, 12 mois ; copie hors site par l'hébergement. « Tester une
  restauration » relit la dernière archive dans une base temporaire, puis la supprime.
- **Appairage par QR code** ([ADR 0008](docs/adr/0008-qr-code-appairage.md)) : le dialogue affiche l'adresse `/kiosk?pair=CODE`
  de la crèche ; la tablette qui l'ouvre s'appaire seule.
- **Abonnement** : formule, places, postes, tablettes, échéance, écrits par l'éditeur (`creche.sh abonnement`), avec effet
  immédiat. Sans abonnement, le logiciel est en évaluation pendant 30 jours.

## Exploitation

Créer une crèche, l'abonnement, les mises à jour, les sauvegardes et la restauration : [docs/EXPLOITATION.md](docs/EXPLOITATION.md).

## Transmissions et portail parents

- **Transmissions** : sur la tablette, bouton « Journée » de la grille. On choisit un ou plusieurs enfants présents,
  puis Repas (biberon compris), Sieste, Change, Médicament ([ADR 0014](docs/adr/0014-medicaments-transmis-aux-parents.md)), Activité, Photo ou Mot. Tout passe par la file hors connexion, sauf les photos (en ligne,
  réduites à 1600 px). Au bureau : fiche enfant › Journée et portail.
- **Portail parents** (optionnel, module `PORTAIL_PARENTS`, [ADR 0010](docs/adr/0010-portail-parents-flux-de-donnees.md)) :
  - activation dans Paramètres › Portail parents, avec l'adresse et le code d'activation de l'hébergeur ;
  - l'instance de la crèche **envoie** chaque minute ce qui est partagé, et **lit** les absences, messages et signatures
    des parents ; aucune connexion entrante, aucune donnée de santé sauf partage explicite de la direction ;
  - une photo ne sort que si elle est marquée « à partager » et si chaque enfant photographié a l'autorisation PHOTOS ;
  - absences signalées : tableau de bord, à confirmer avec un motif (elles entrent alors en facturation) ;
  - signatures reçues : Paramètres › Portail parents ou fiche enfant, à enregistrer (contrat signé, autorisation accordée
    ou refusée) ;
  - « Repas non facturé si l'absence est signalée avant » : vide par défaut ; l'application des parents n'en parle que
    si l'heure est réglée ;
  - désactivation : saisir DÉSACTIVER ; le portail efface tout ce qu'il détient pour la crèche.
- Développement du portail : voir [parents-portal/README.md](parents-portal/README.md).

## Design system

Les tokens des maquettes (`docs/design/`) sont dans [`web/src/index.css`](web/src/index.css) :
couleurs (`lagon`, `niaouli`, `terre`, états, sections Bébés / Moyens / Grands), typographie (Source Sans 3,
Fraunces ; `text-display`, `text-title-1`… `text-touch-*`), rayons (6 / 10 / 16 px), ombres et dimensions
(`w-sidebar`, `h-topbar`, `h-touch`…). Les composants shadcn/ui utilisent ces tokens.

## Parcours produit conseillé

1. Valider le périmètre dans `01-concept.md`.
2. Faire valider les règles (encadrement, aides, agrément) auprès de la DPASS Province Sud avant la mise en service.
