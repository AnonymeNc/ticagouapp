# Ti'Cagou — logiciel de gestion pour crèches et garderies de Nouvelle-Calédonie

Application web hébergée, une instance par crèche ([ADR 0013](docs/adr/0013-web-heberge-instance-par-creche.md)), et
trois applications mobiles ([ADR 0023](docs/adr/0023-applications-mobiles-capacitor.md)) : **Ti'Cagou Direction**
(iPhone, Android), **Ti'Cagou Pointage** (tablette Android) et **Ti'Cagou Parents** (iPhone, Android). Enfants et
familles, inscriptions, contrats, pointage tablette (hors connexion compris), taux d'encadrement en temps réel, repas,
facturation XPF avec aides, personnel, portail parents, sauvegardes. Une seule formule, tout compris
([ADR 0021](docs/adr/0021-formule-unique.md)).

## Arborescence

| Dossier / fichier | Rôle |
|---|---|
| `server/` | API REST Spring Boot 3 (Java 21, PostgreSQL 17, Flyway) — `/api/v1` |
| `web/` | React 19 : bureau dans le navigateur, route `/kiosk` (tablette d'accueil), front des applications Direction et Pointage |
| `parents-portal/` | Portail parents hébergé : service Spring Boot (`server/`), application des parents (`web/`), contrat de synchronisation (`contract/`) — [ADR 0010](docs/adr/0010-portail-parents-flux-de-donnees.md) |
| `console/` | Console de l'éditeur : parc des crèches, facturation des abonnements, ordres — [docs/CONSOLE.md](docs/CONSOLE.md) |
| `mobile/` | Coques Capacitor des trois applications (`direction/`, `pointage/`, `parents/`) et leurs scripts |
| `deploy/` | Image Docker, socle et `creche.sh` : créer et exploiter les crèches |
| `scripts/` | `tester-en-local.ps1`, `tous-les-tests.ps1`, `secrets-mobile.ps1`, données de démonstration, captures |
| `outils/publicite/`, `outils/presentations/` | Prospectus, maquette des offres, présentations PowerPoint ([docs/commercial/presentations](docs/commercial/presentations/README.md)) |
| `docs/design/` | Maquettes : **référence visuelle** |
| `docs/adr/` | Décisions d'architecture (0001 à 0028) |
| `docs/wiki/` | Guides d'utilisation et supports de formation |
| `docs/commercial/` | Barème, démonstrations, argumentaire, fiches des stores, présentations |
| `CLAUDE.md` | Règles métier et conventions (lues par Claude Code) |
| `01-concept.md`, `02-techno.md` | Cadrage produit et technique |

## Documentation

| Besoin | Document |
|---|---|
| Essayer Ti'Cagou sur son PC, lancer les tests | [docs/TESTER-EN-LOCAL.md](docs/TESTER-EN-LOCAL.md) |
| Installer le serveur, créer une crèche, mettre à jour, restaurer | [docs/EXPLOITATION.md](docs/EXPLOITATION.md) |
| Gérer ses crèches au quotidien | [docs/CONSOLE.md](docs/CONSOLE.md) |
| Publier les applications dans les stores | [docs/PUBLIER-LES-APPLICATIONS.md](docs/PUBLIER-LES-APPLICATIONS.md) |
| Ce qu'il reste à faire avant de vendre | [docs/RESTE-A-FAIRE.md](docs/RESTE-A-FAIRE.md) |
| Utiliser et former | [docs/wiki/README.md](docs/wiki/README.md) |
| Vendre, faire une démonstration | [docs/commercial/](docs/commercial/demonstrations.md) |

## Démarrer en développement

Prérequis : JDK 21, Node 22+ et pnpm 10, Docker Desktop. Tout lancer, avec les données de démonstration et
l'application Direction simulée :

```powershell
powershell -ExecutionPolicy Bypass -File scripts	ester-en-local.ps1 -Demo -Mobile
```

Bureau : http://localhost:1420 (`nathalie.kaloi` / `Niaouli-2026!`), tablette : http://localhost:1420/kiosk,
application Direction : http://localhost:1431. Lancement à la main, comptes et parcours :
[docs/TESTER-EN-LOCAL.md](docs/TESTER-EN-LOCAL.md). Variables du serveur : `server/src/main/resources/application.yml`
et [EXPLOITATION.md](docs/EXPLOITATION.md).

## Tests

```powershell
powershell -ExecutionPolicy Bypass -File scripts	ous-les-tests.ps1
```

Serveur, portail, console, les trois fronts et la cohérence des plugins mobiles, avec un bilan. La CI GitHub
(`.github/workflows/ci.yml`) fait la même chose à chaque envoi sur `main` et à chaque demande de fusion, et compile
les applications Android et iOS.

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
  sauvegardes, tablettes et abonnement. Une seule formule, tout compris ([ADR 0021](docs/adr/0021-formule-unique.md)).
- **Reprise des données** ([ADR 0016](docs/adr/0016-reprise-des-donnees.md)) : modèle Excel, aperçu sans écriture, relecture de la
  santé, import tout ou rien. Guide : [docs/wiki/14-reprise-des-donnees.md](docs/wiki/14-reprise-des-donnees.md).
- **Sauvegardes** ([ADR 0007](docs/adr/0007-sauvegardes-chiffrees.md)) : chaque nuit ou à la demande, `pg_dump` et documents
  dans une archive AES-256-GCM ; rotation 7 jours, 4 semaines, 12 mois ; copie hors site par l'hébergement. « Tester une
  restauration » relit la dernière archive dans une base temporaire, puis la supprime.
- **Appairage par QR code** ([ADR 0008](docs/adr/0008-qr-code-appairage.md)) : le dialogue affiche l'adresse `/kiosk?pair=CODE`
  de la crèche ; la tablette qui l'ouvre s'appaire seule.
- **Abonnement** : formule, places, postes, tablettes, échéance, écrits par l'éditeur (`creche.sh abonnement`), avec effet
  immédiat. Sans abonnement, le logiciel est en évaluation pendant 30 jours.

## Exploitation

Créer une crèche, l'abonnement, les mises à jour, les sauvegardes et la restauration : [docs/EXPLOITATION.md](docs/EXPLOITATION.md).
Se connecter à la console de l'éditeur et gérer ses crèches au quotidien : [docs/CONSOLE.md](docs/CONSOLE.md).

## Transmissions et portail parents

- **Transmissions** : sur la tablette, bouton « Journée » de la grille. On choisit un ou plusieurs enfants présents,
  puis Repas (biberon compris), Sieste, Change, Médicament ([ADR 0014](docs/adr/0014-medicaments-transmis-aux-parents.md)), Activité, Photo ou Mot. Tout passe par la file hors connexion, sauf les photos (en ligne,
  réduites à 1600 px). Au bureau : fiche enfant › Journée et portail.
- **Portail parents** (activé par la direction, [ADR 0010](docs/adr/0010-portail-parents-flux-de-donnees.md)) :
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

## Avant la mise en service

Faire valider les règles (encadrement, aides, agrément, TGC) auprès de la DPASS Province Sud et d'un expert-comptable
(`01-concept.md`), puis suivre [docs/RESTE-A-FAIRE.md](docs/RESTE-A-FAIRE.md).
