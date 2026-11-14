# ADR 0007 — Sauvegardes chiffrées des crèches

- **Statut** : acceptée
- **Date** : 01/11/2026, mise à jour le 15/11/2026

## Contexte

Les données d'une crèche (dossiers d'enfants, santé, factures) vivent dans son instance hébergée
([ADR 0013](0013-web-heberge-instance-par-creche.md)). Une panne du serveur, une erreur ou un rançongiciel ne doit pas
les faire perdre. La sauvegarde doit être chiffrée (données de santé d'enfants), vérifiable sans toucher aux données en
service, et restaurable par l'éditeur.

## Décision

- **Contenu** : `pg_dump --format=custom` de la base, plus les documents du dossier de stockage (déjà chiffrés au
  repos, ADR 0003), plus un `manifest.json` (date, version du schéma, nombre de lignes de chaque table). Les trois sont
  réunis dans une archive zip (`.tcb`).
- **Chiffrement** : l'archive entière est chiffrée en AES-256-GCM par blocs de 1 Mio (nonce = préfixe aléatoire +
  numéro du bloc, numéro et indicateur « dernier bloc » authentifiés). La clé est dérivée de la **clé de secours** par
  PBKDF2-HMAC-SHA256 (210 000 itérations, sel aléatoire par archive). Format : `TICAGOU-BK1 | sel | préfixe | blocs`.
  Aucune dépendance en plus du JDK.
- **Clé de secours** : générée par `creche.sh creer`, gardée **hors de la base** dans `creche.env`
  (`TICAGOU_BACKUP_RECOVERYKEY`) et dans le coffre de l'éditeur. Une copie des archives ne contient jamais la clé.
- **Emplacement et heure** : dossier `/data/backups` de l'instance, heure tirée au hasard entre 1 h et 5 h par
  `creche.sh` ; la direction ne règle rien.
- **Conservation** : toutes les sauvegardes des 7 derniers jours, puis la première de chaque semaine pendant 4 semaines
  et la première de chaque mois pendant 12 mois. Avant chaque migration du schéma, une sauvegarde de la base est écrite.
- **Copie hors site** : `deploy/sauvegarde-hors-site.sh` copie les archives chez un second hébergeur (rclone), sans
  jamais effacer.
- **Dans l'application** : la direction voit l'état, lance une sauvegarde et un **test de restauration** : la dernière
  archive est déchiffrée, restaurée par `pg_restore` dans une base temporaire `ticagou_test_restauration_*`, ses tables
  sont comptées et comparées au manifeste, puis la base est supprimée. Un échec prévient la direction (notification,
  [ADR 0026](0026-notifications-natives.md)) et la supervision ([ADR 0012](0012-supervision-a-distance.md)).
- **Restauration réelle** : `creche.sh restaurer`, par l'éditeur, jamais un bouton de l'interface.

## Alternatives écartées

- **Export applicatif** des tables par le serveur : restauration moins standard et plus lente.
- **Chiffrement par GPG ou 7-Zip** : outils de plus à installer et à piloter ; le JDK suffit.
- **Sauvegarde du volume Docker seul** : non chiffrée, non vérifiable sans arrêter l'instance.

## Conséquences

- Le rôle PostgreSQL de l'instance peut créer une base (test de restauration) ; `creche.sh` le prévoit.
- Perdre `creche.env` et le coffre rend les archives d'une crèche illisibles : il va au coffre à chaque création.
