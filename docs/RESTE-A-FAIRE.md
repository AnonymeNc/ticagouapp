# Ce qu'il reste à faire

Mis à jour le 15/11/2026. Le logiciel, les trois applications et la CI sont prêts ; ce qui suit attend surtout des
comptes, des paiements ou des décisions de l'éditeur. Liste commerciale complète :
[checklist-commercialisation.md](commercial/checklist-commercialisation.md).

## 1. Fait

- **CI complète et verte** sur `main` (15/11/2026) : serveur, portail, console, bureau et tablette, image Docker,
  compilation **Android** et **iOS**. Elle tourne à chaque envoi sur `main` et à chaque demande de fusion.
- **Dépôt public** `AnonymeNc/ticagouapp` : minutes GitHub Actions gratuites et illimitées, analyse des secrets et
  alertes de sécurité Dependabot activées. Les anciens dépôts `ticagou` et `ticagouweb` n'exécutent plus d'Actions.
- **`main` protégée** : une demande de fusion doit passer les 7 contrôles et avoir ses discussions résolues ; pas de
  force-push ni de suppression. L'administrateur du dépôt peut encore envoyer directement sur `main`.
- **Clé Android d'importation** et ses 4 secrets GitHub générés (`scripts/secrets-mobile.ps1`) ; clé et mot de passe
  dans `Documents\Ticagou-coffre` sur le PC de l'éditeur.
- Dependabot : une demande de fusion par dossier et par mois (versions mineures et correctives regroupées).

### Si le dépôt redevient privé

1. GitHub › Settings › General › Danger Zone › Change visibility › Private.
2. Supprimez la variable `MOBILE_CI` (Settings › Secrets and variables › Actions › Variables) : Android et iOS ne
   tourneront plus qu'à la demande (Actions › Tests › Run workflow, case « mobile »).
3. Le quota gratuit est de 2 000 minutes par mois, **tous dépôts privés confondus**, et une minute macOS en compte 10 :
   un run complet avec iOS consomme environ 60 minutes. Au-delà, les jobs ne démarrent plus (« recent account payments
   have failed or your spending limit needs to be increased »).
4. La protection de `main` disparaît sur un dépôt privé avec l'offre gratuite : il faut GitHub Pro (4 $ par mois,
   3 000 minutes) pour la garder.
5. Serveur de production : passez le clone en SSH avec une clé de déploiement ([EXPLOITATION.md § 2.5](EXPLOITATION.md#25-récupérer-ticagou)).

## 2. À faire par l'éditeur

| Quoi | Guide |
|---|---|
| Numéro D-U-N-S de la société, puis comptes **Google Play** (25 $) et **Apple** (99 $/an) | [PUBLIER-LES-APPLICATIONS.md](PUBLIER-LES-APPLICATIONS.md) A à C |
| Projet **Firebase**, clés APNs et FCM sur le serveur | même guide, D et E |
| Première version : test interne, TestFlight, essais sur de vrais appareils, soumission | même guide, F |
| Politique de confidentialité publiée sur le site | [politique-confidentialite.md](commercial/stores/politique-confidentialite.md) |
| Captures des stores refaites un matin de semaine (crèche pleine) ; captures de l'application des parents ; `facturation-bureau.png` (prise pendant le calcul) et `fiche-sante-bureau.png` (liste au lieu de la fiche) à refaire, puis `pnpm generer` dans `outils/presentations` | `node scripts/captures/captures.mjs`, [captures/README.md](commercial/captures/README.md) |
| Avis juridique sur l'hébergement des données de santé, puis choix de l'hébergeur | [ADR 0013](adr/0013-web-heberge-instance-par-creche.md) |
| Serveur de production, crèche témoin, crèche de démonstration `demo-stores` | [EXPLOITATION.md](EXPLOITATION.md) |
| Logo définitif (icônes : `mobile/scripts/icones.ps1`) | — |

## 3. Prérequis au déploiement d'une crèche

- Un serveur Linux avec Docker, un nom de domaine (`DOMAINE_BASE`) et ses enregistrements DNS (crèches, portail,
  console, annuaire), HTTPS par Caddy.
- Un second hébergeur pour la copie hors site des sauvegardes (rclone).
- `deploy/secrets.sh` (tous les secrets du socle d'un coup), `./creche.sh socle`, `./creche.sh creer <nom>`,
  `./creche.sh annuaire` ; `creche.env` de chaque crèche au coffre.
- Internet de la crèche vérifié ; une tablette Android (Android 10+, 10 pouces, appareil photo) pour l'accueil.

## 4. Points ouverts dans le logiciel

**Règles à faire valider** (pas des erreurs) :
- Une aide en montant fixe est proratisée sur les jours calendaires de sa validité, le forfait sur les jours
  d'ouverture.
- Les jours fériés et fermetures ne sont pas retirés des jours d'ouverture (calendrier des fermetures à créer).
- La TGC est calculée sur les frais de garde avant déduction des aides : à confirmer avec l'expert-comptable.
- La lecture seule (abonnement échu) bloque aussi le pointage des tablettes ([ADR 0011](adr/0011-licence-lecture-seule-postes.md)).

**Technique** :
- Paginer `GET /billing/invoices` sans mois ; politique de conservation des journaux d'audit (environ 400 000 lignes
  par an pour 5 crèches).
- Ne plus journaliser en erreur la fermeture d'un flux temps réel par le navigateur (`StaffingBroadcaster.send`).
- Révocation de ses propres sessions par le parent ([securite-applications-mobiles.md](securite-applications-mobiles.md) § 7).
- `outils/publicite` : dépendances de Puppeteer signalées par Dependabot (`extract-zip`, `basic-ftp`), outil interne
  non livré.

**À vérifier sur de vrais appareils** : Face ID et empreinte, coffre du téléphone, mode kiosque, notifications, scan des
QR, taille des boutons du kiosque en portrait 768 px, contraste en plein soleil.

**Idées pour la console** : liste « à appeler cette semaine » (évaluations et abonnements qui finissent), temps de
support par crèche, notes client, message aux directrices, envoi des factures et relances des familles par e-mail,
paiement en ligne.
