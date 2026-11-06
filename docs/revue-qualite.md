# Revue qualité — 01/11/2026

> Mise à jour du 05/11/2026 ([ADR 0013](adr/0013-web-heberge-instance-par-creche.md)) : le kiosque chiffre sa file et son
> cache, les clés de documents et de secours sortent de la base en production, l'installeur et ses constats ont disparu.

Revue de l'état après la phase 9 (commit 479628b) sur quatre axes :
- sécurité et confidentialité ;
- calculs ;
- performance ;
- ergonomie du kiosque.

« Corrigé » signifie corrigé dans cette revue. Le reste est à planifier.

## 1. Sécurité et confidentialité des données d'enfants

### Constats positifs
- **Droits par rôle** : chaque contrôleur porte un `@PreAuthorize`, au niveau de la classe ou de chaque méthode.
  - Les seuls endpoints ouverts sont voulus : santé du serveur, assistant, appairage, certificat de l'autorité,
    mises à jour.
  - `/staff/planning/me` et `/staff/timesheets/me` ne renvoient que les données de l'utilisateur connecté.
  - Fiche complète et documents : direction et éducatrices seulement. La cuisine ne voit que la liste (allergies,
    régimes). La comptabilité n'a aucun accès aux enfants.
- **Isolation entre structures** : les chargements passent par `findByIdAndStructureId` ou par un parent déjà
  vérifié.
- **Journal des consultations** : fiche, documents, pointage, export (`ChildAccessLogger`). Il est en ajout seul,
  comme `audit_event` (trigger).
- **Journal d'audit** : les modifications de santé n'y laissent que des compteurs (`allergies: 2, pai: true`), jamais
  leur contenu. Les PIN ne sont jamais stockés côté serveur.
- **Chiffrement** :
  - documents en AES-256-GCM (ADR 0003) ;
  - sauvegardes chiffrées (ADR 0007) ;
  - HTTPS sur le réseau local avec épinglage côté client bureau (ADR 0009) ;
  - export et purge d'une famille présents.

### Corrigé
- **Lignes PostgreSQL dans les journaux** : sur une erreur d'intégrité, PostgreSQL renvoie
  `Failing row contains (…)` avec toute la ligne, qui finissait dans les journaux du serveur via
  `ApiExceptionHandler`. `logServerErrorDetail: false` est maintenant posé sur le pilote JDBC (`application.yml`).
- **Motif d'absence dans l'audit** : l'audit des absences d'enfants enregistrait le **motif** (« Maladie »,
  « Hospitalisation »). Seul son identifiant est désormais journalisé (`AbsenceService.details`) ; vérifié dans
  `AbsenceIT`.
- **Documentation de l'API** : Swagger UI et `/v3/api-docs` sont fermés une fois installé (`TICAGOU_API_DOCS=false`,
  posé dans l'image). Ils restent ouverts en développement.

## 2. Exactitude des calculs

- **Encadrement** (`staffing/RatioCalculator`) :
  - calcul exact en entiers (PPCM des ratios), règle la plus exigeante quand plusieurs s'appliquent ;
  - un enfant sans règle se voit appliquer la règle la plus stricte, avec un indicateur ;
  - « À la limite » quand un enfant de plus dépasserait.
  - Aucun défaut trouvé.
- **Repas** (`meal/MealCounter`) : ordre de priorité correction > pointage passé > prévision du contrat ; paniers repas
  comptés à part. Aucun défaut trouvé.
  - Corrigé : accord « dont 1 mixé, 2 hachés » au tableau de bord.
- **Facturation** (`billing/InvoiceCalculator`, `Xpf`) :
  - tout est en `long` ;
  - arrondi par ligne symétrique pour les montants négatifs ;
  - arrondi du total selon la règle de la structure ;
  - aucune virgule flottante.

  Points de règle **à faire valider** (pas des bugs) :
  - Une aide en montant fixe est proratisée sur les **jours calendaires** de validité de l'aide, alors que le forfait
    l'est sur les **jours d'ouverture**. Une aide fixe valable tout le mois reste entière même si l'enfant n'est
    inscrit qu'en fin de mois ; elle n'est limitée que par le montant restant à facturer.
  - Les jours fériés et les fermetures ne sont pas retirés des jours d'ouverture du prorata : le calendrier des
    fermetures n'existe pas encore.
  - La TGC est calculée sur les frais de garde avant déduction des aides : à confirmer avec l'expert-comptable.

## 3. Performance — 5 structures × 80 enfants × 3 ans

Une base de volume a été générée à partir d'une copie de la base de dev (script de la revue, non versionné) :

| Table | Lignes |
|---|---|
| `audit_event` | 1,2 M |
| `child_record_access` | 298 000 |
| `child_attendance` | 151 000 |
| `invoice_line` | 57 000 |
| `staff_attendance` | 48 000 |
| `invoice` | 14 000 |
| `payment` | 13 500 |

**Temps du serveur** sur le poste de dev (médiane de 3 appels, en ms) :

| Écran / endpoint | ms | Remarque |
|---|---|---|
| Tableau de bord `today` / `kpis` | 58 / 140 | |
| Présences du jour | 124 | |
| Encadrement | 30 | |
| **Kiosque `today`** | 453 → **140** | 40 Ko. Corrigé : les personnes autorisées étaient chargées par deux requêtes par enfant ; elles le sont maintenant en deux requêtes pour toute la structure (`AttendanceService.pickupLists`), réponse identique. |
| Enfants (liste / fiche) | 26 / 44 | |
| Factures du mois | 11 | |
| **Factures sans filtre** | 56 | **1 Mo** : les 3 ans de la structure, sans pagination. |
| Encaissements | 48 | |
| Prévisualisation « Facturer le mois » | 225 | |
| Vue cuisine / trimestre repas | 81 / 71 | |
| Journal d'audit (page 1 / page 2000 / filtre) | 47 / 90 / 12–60 | |
| Prévision de l'encadrement / feuilles d'heures | 25 / 26 | |
| Capacité / demandes d'inscription | 86 / 81 | |

Conclusion : après correction, tout reste sous 250 ms. Les index existants couvrent les accès par structure
et jour, famille et mois, facture.

**À traiter** :
- Paginer `GET /billing/invoices` sans mois, ou imposer un mois ou un intervalle.
- Prévoir une politique de conservation :
  - `audit_event` et `child_record_access` : environ 400 000 lignes par an pour 5 structures, en ajout seul ;
  - archivage annuel à décider (durée légale de conservation des journaux).
- L'encadrement est recalculé chaque minute pour chaque structure : peu coûteux aujourd'hui (30 ms), à mesurer au-delà
  de 5 structures.

## 4. Ergonomie tactile du kiosque

**Conforme** :
- Les cibles tactiles utilisent `h-touch` (56 px), `h-touch-lg` (72 px) pour le pavé PIN, ou des cartes de 96 px
  (choix de la personne).
- Textes `text-touch-*` de 18 à 28 px.
- Bandeau hors connexion, état d'encadrement toujours visible.
- La page d'appairage `/appairer` suit les mêmes règles.

**À vérifier sur une vraie tablette** (non mesurable ici, le volet navigateur ne s'affichait pas) :
- Avatar de l'utilisateur dans l'en-tête : cercle de 40 px (`size-10`) dans un bouton de 56 px. Vérifier que toute la
  pastille est cliquable.
- Les boutons de `KioskHomePage` (tuiles enfants) et de `DepartureScreen` (lignes 90 et 114) n'ont pas de hauteur
  fixée ; leur taille dépend du contenu. À contrôler en portrait 768 px.
- Contraste des pastilles d'état sur fond coloré en plein soleil (accueil extérieur) ; tester la luminosité minimale.
- Confirmation des actions irréversibles (départ avec une personne non autorisée) : déjà doublée par le PIN de la
  direction.

## Constat de journal pendant la revue
- Le flux temps réel de l'encadrement (SSE) écrit toutes les 25 s vers les PC connectés. Quand un PC se ferme,
  Tomcat journalise une pile d'erreurs complète (`IOException : connexion abandonnée`, `StaffingBroadcaster.send`),
  alors que le flux est déjà retiré proprement. C'est du bruit qui masquera de vraies erreurs.
  - Proposition : intercepter `AsyncRequestNotUsableException` / la déconnexion du client dans
    `ApiExceptionHandler` sans la journaliser en erreur, avec un test qui coupe la connexion.

## Vérification
- Serveur : `mvnw verify` → 144 tests unitaires et 166 tests d'intégration, tous verts.
- Front : `pnpm typecheck`, puis `pnpm test` → 105 tests verts.
- La base de volume `ticagou_perf` a été supprimée après les mesures.
