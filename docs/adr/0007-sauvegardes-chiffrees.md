# ADR 0007 — Sauvegardes chiffrées du serveur local

- **Statut** : acceptée, modifiée par l'[ADR 0013](0013-web-heberge-instance-par-creche.md) (dossier, heure et clé gérés par l'éditeur, copie hors site)
- **Date** : 01/11/2026

## Contexte

Les données d'une crèche (dossiers d'enfants, santé, factures) ne vivent que sur le serveur local. Une panne, un vol
ou un rançongiciel ne doit pas les faire perdre. La sauvegarde doit fonctionner sans internet, être chiffrée (données
de santé d'enfants), vérifiable sans toucher aux données en service, et restaurable par l'installeur.

## Décision

- **Contenu** : `pg_dump --format=custom` de la base (outil installé avec PostgreSQL sur le serveur), plus les
  documents du dossier de stockage (déjà chiffrés au repos, ADR 0003), plus un `manifest.json` (date, version du
  schéma, nombre de lignes de chaque table). Les trois sont réunis dans une archive zip.
- **Chiffrement** : l'archive entière est chiffrée en AES-256-GCM par blocs de 1 Mio (nonce = préfixe aléatoire +
  numéro du bloc, numéro et indicateur « dernier bloc » authentifiés). La clé est dérivée de la **clé de
  récupération** par PBKDF2-HMAC-SHA256 (210 000 itérations, sel aléatoire par archive). Format :
  `TICAGOU-BK1 | sel | préfixe | blocs`. Aucune dépendance en plus du JDK.
- **Clé de récupération** : 24 caractères sans ambiguïté (≈ 120 bits), générée une fois et conservée dans
  `server_secret` pour que la sauvegarde de la nuit tourne seule. La direction l'imprime (PDF) et la garde hors de la
  structure : c'est elle qui permet de restaurer après la perte du serveur. La date de remise est notée.
- **Copies** : une copie sur place (disque USB, NAS) obligatoire, une copie hors site facultative (autre dossier).
  Conservation : toutes les sauvegardes des 7 derniers jours, puis la première de chaque semaine pendant 4 semaines et
  la première de chaque mois pendant 12 mois (réglable). Avant chaque migration du schéma, une sauvegarde de la base
  (sans les documents) est écrite dans le dossier local `backups` (phase 9).
- **Planification** : chaque nuit à l'heure réglée (02:00), une seule fois par jour ; à la demande depuis
  Paramètres. Une seule opération à la fois, en arrière-plan ; l'historique note le résultat et la cause d'un échec.
- **Test de restauration** : la dernière archive est déchiffrée, restaurée par `pg_restore` dans une base temporaire
  `ticagou_test_restauration_*`, ses tables sont comptées et comparées au manifeste, puis la base est supprimée.
- **Restauration réelle** : procédure de l'installeur (arrêt du service, `pg_restore` sur une base neuve, recopie des
  documents), jamais un bouton de l'interface.

## Alternatives écartées

- **Export applicatif** des tables par le serveur : pas d'outil externe, mais une restauration moins standard et plus lente.
- **Chiffrement par GPG ou 7-Zip** : outils de plus à installer et à piloter ; le JDK suffit.
- **Stockage en ligne Ti’Cagou** : demande un service hébergé et une connexion ; reporté (la copie hors site est un dossier).

## Conséquences

- Le compte PostgreSQL de l'application doit pouvoir créer une base (test de restauration) : c'est le cas avec
  l'installeur ; sinon le test échoue avec un message clair, la sauvegarde elle-même reste possible.
- `ticagou.backup.pg-bin` indique le dossier de pg_dump s'il n'est ni dans le PATH ni dans
  `C:\Program Files\PostgreSQL\*\bin`.
- Perdre la clé de récupération rend les archives illisibles : l'écran le rappelle et propose de la réimprimer.
