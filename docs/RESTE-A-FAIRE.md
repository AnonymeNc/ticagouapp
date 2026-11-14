# Ce qu'il reste à faire : CI, stores, démonstrations, déploiement

Mis à jour le 14/11/2026. Le code des phases 0 à 9 est livré ; ce qui suit dépend de vos actions (comptes, paiements,
secrets), que Claude ne fait pas à votre place.

## 1. Une CI complètement fonctionnelle

1. **Facturation GitHub** : github.com › Settings › Billing & plans. Réglez le moyen de paiement ou la limite de
   dépenses : aujourd'hui aucun job ne démarre. Le dépôt est privé, donc les minutes sont décomptées (macOS compte
   10 fois).
2. **Premier run complet** : Actions › Tests › *Run workflow*, case « mobile » cochée. Tout doit être vert : serveur,
   portail, console, fronts, image Docker, Android, iOS. En local, `scripts\tous-les-tests.ps1` fait la même chose
   sans les compilations natives.
3. **Protection de `main`** (conseillé) : Settings › Branches › règle sur `main`, tests requis avant fusion.
4. **Dependabot** : il ouvre des PR chaque semaine (Capacitor exclu, il se met à jour à la main dans `web/`,
   `parents-portal/web/` et `mobile/` ensemble). Une PR verte se fusionne, une PR en conflit se relance avec le
   commentaire `@dependabot rebase`.
5. **Secrets de publication** (après le § 2) : Settings › Secrets and variables › Actions, liste complète dans
   [EXPLOITATION.md § 7 bis](EXPLOITATION.md).

## 2. Publier sur les stores

Prérequis communs : la **société éditrice** et son **numéro D-U-N-S** (gratuit, demande sur le site de Dun & Bradstreet,
1 à 3 semaines), une adresse e-mail et un téléphone de l'éditeur, la [politique de confidentialité](commercial/stores/politique-confidentialite.md)
publiée à une adresse publique.

**Google Play** (Direction, Pointage, Parents)
1. Compte développeur **organisation** sur play.google.com/console (25 $ une fois), vérification de l'identité.
2. Créer les trois applications (`nc.ticagou.direction`, `nc.ticagou.pointage`, `nc.ticagou.parents`), remplir les
   fiches ([fiches-stores.md](commercial/stores/fiches-stores.md)), la section « Sécurité des données »
   ([declarations-confidentialite.md](commercial/stores/declarations-confidentialite.md)), les captures
   (`docs/commercial/captures/stores`, à refaire un matin de semaine avec `scripts/captures/captures.mjs`).
3. Clé de signature (`keytool`, EXPLOITATION.md § 7 bis), **sauvegardée en deux endroits** : perdue, plus de mise à jour.
4. Firebase : un projet, les trois applications Android, `google-services.json` dans `mobile/<app>/android/app/`, le
   compte de service FCM dans `socle.env` du serveur (notifications).
5. Workflow « Publication mobile » : AAB signés en artefact, à déposer en **test interne**, puis test fermé
   (12 testeurs pendant 14 jours exigés pour un nouveau compte), puis production.

**App Store** (Direction, Parents ; Pointage est Android seulement)
1. Apple Developer Program **organisation** (99 $ par an), avec le D-U-N-S.
2. App Store Connect : créer les deux applications, les fiches, l'étiquette de confidentialité, un **compte de
   démonstration** pour la revue Apple (crèche de démonstration hébergée).
3. Clé APNs (notifications) dans `socle.env`, clé d'API App Store Connect dans les secrets GitHub.
4. Workflow « Publication mobile » : envoi à **TestFlight**, essais sur vos iPhone, puis soumission à la revue.

## 3. Démonstrations et présentations sur un téléphone

- **Sans store, tout de suite** : la crèche de démonstration hébergée s'ouvre dans le navigateur du téléphone
  (direction et `/kiosk` sur une tablette), et l'application des parents fonctionne comme application web installable
  (« Ajouter à l'écran d'accueil »).
- **Sur le PC** : `scripts\tester-en-local.ps1 -Demo -Mobile`, puis F12 › mode téléphone (http://localhost:1431 pour
  Direction, http://localhost:5180 pour Parents). Pratique pour une présentation projetée.
- **Vraies applications Android** : APK de test produits par la CI (Run workflow, case « mobile »), installés sur un
  téléphone ou une tablette de démonstration ; ils se connectent à la crèche de démonstration **hébergée** (https).
- **iPhone** : par TestFlight seulement, une fois le compte Apple ouvert.
- Déroulé : [demonstrations.md](commercial/demonstrations.md), section « Démonstration mobile » ; captures prêtes dans
  `docs/commercial/captures/mobile`.

## 4. Prérequis au déploiement d'une crèche

- Un serveur Linux avec Docker et un nom de domaine (`DOMAINE_BASE`) pointant dessus, HTTPS par Caddy.
- Un second hébergeur pour la copie hors site des sauvegardes.
- Le socle : `deploy/socle.env` rempli (mots de passe, push APNs/FCM une fois les comptes ouverts), puis
  `./creche.sh socle`, `./creche.sh creer <nom>`, `./creche.sh annuaire` (code de la crèche pour les applications).
- Le portail parents et la console de l'éditeur sur le même socle.
- Pas à pas : [EXPLOITATION.md](EXPLOITATION.md) et [CONSOLE.md](CONSOLE.md).

## 5. Nettoyage de la documentation (en cours)

Objectif : ne garder que ce qui décrit la version actuelle (web hébergé + applications mobiles), sans la V1 installée.
Inventaire fait le 14/11/2026 ; reste à traiter :
- ADR remplacés à supprimer : 0009 (HTTPS sur le réseau local, mDNS), 0015 (formules et modules) ; ADR 0001, 0002,
  0005, 0007, 0011, 0012, 0013 à réécrire en intégrant leurs modifications (plus de Tauri, de clé signée, de serveur
  local).
- Plans terminés à retirer ou archiver : `plan-alignement-meeko.md`, `plan-console-editeur.md`, `revue-qualite.md`,
  `etude-application-mobile.md`, puis `plan-application-mobile.md` (décisions reportées dans les ADR 0023 à 0028).
- Mentions de la V1 à retirer : `README.md`, `CLAUDE.md`, `01-concept.md`, `02-techno.md` (schéma sans les
  applications mobiles), `EXPLOITATION.md` (13 mentions), `TESTER-EN-LOCAL.md`, `console/README.md`,
  `parents-portal/README.md`, `docs/wiki/README.md` (supports PowerPoint de la V1).
