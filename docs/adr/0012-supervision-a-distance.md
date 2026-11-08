# ADR 0012 — Supervision à distance des serveurs de crèche

- **Statut** : acceptée, modifiée par l'[ADR 0013](0013-web-heberge-instance-par-creche.md) (activée d'office par l'éditeur)
- **Date** : 03/11/2026

## Contexte

Le serveur de la crèche tourne sur un PC de bureau, sans informaticien sur place. Une sauvegarde qui ne se fait
plus, un disque plein ou une licence qui expire ne sont souvent vus qu'au moment où il est trop tard. Le prestataire
veut le savoir avant la directrice, sans accéder aux données de la crèche.

## Décision

- **Choix de la directrice** : la supervision est désactivée par défaut. La direction l'active dans Paramètres ›
  Supervision avec l'adresse du service et un code à usage unique (préfixe `S`) remis par le prestataire. Elle peut
  la couper à tout moment, et chaque changement est journalisé.
- **Même service hébergé que le portail parents, mais indépendant** :
  - paire Ed25519 distincte (`server_secret` `monitor-key-…`) ;
  - tables distinctes (`supervision_site`, `supervision_alert`) ;
  - une crèche sans portail parents peut être supervisée.
- **Sens des connexions** : comme pour le portail (ADR 0010), le serveur de la crèche ouvre toujours la connexion.
  `POST /supervision/v1/status` est signé comme la synchronisation : en-têtes `X-Ticagou-*`, 5 minutes d'écart au
  plus, rejeu refusé. Le service n'appelle jamais la crèche.
- **Contenu de l'état** (`monitoring/MonitoringStatus`, liste blanche ; le service ne garde que ces champs) :
  - version, état de la base ;
  - sauvegardes : activées, outils présents, retard de plus de 48 h, dernière réussite, dernier test de
    restauration ;
  - espace libre et total du disque des données et de celui des sauvegardes ;
  - licence : état, échéance, date de lecture seule, PC utilisés et permis ;
  - tablettes appairées et muettes depuis 3 jours ;
  - portail parents : état, échecs à la suite, dernière erreur, file d'attente ;
  - nombre de pointages à vérifier ;
  - usage, pour la console de l'éditeur (amendement du 08/11/2026, [ADR 0017](0017-console-editeur.md)) : enfants
    actifs, places du contrat, utilisateurs actifs sur 30 jours, jour de la dernière connexion d'un compte de direction,
    pointages des 7 derniers jours, factures aux familles du mois dernier. Des nombres et une date, rien d'autre.

  Aucun nom, aucune donnée d'enfant, de famille ou du personnel : un test le vérifie, et un autre vérifie que l'usage
  ne contient que des nombres et une date.
- **Rythme** : toutes les 15 minutes, puis attente doublée après chaque échec, jusqu'à 1 h.
- **Alertes** (`supervision/AlertRules`, évaluées à la réception et toutes les 15 minutes) :
  - serveur muet depuis 2 h entre 6 h et 19 h (heure de Nouméa), 12 h la nuit ;
  - sauvegarde en retard ;
  - disque à moins de 10 % ou de 5 Go libres ;
  - licence à renouveler ou expirée ;
  - tablette muette ;
  - portail en échec.

  Un e-mail part à l'ouverture et à la résolution de chaque alerte (une seule alerte ouverte par type et par
  site), plus un récapitulatif à 7 h. Le destinataire est le prestataire (`PORTAL_SUPERVISION_EMAIL`), pas la
  crèche.
- **Hébergeur** : `POST /admin/v1/supervision-codes`, `GET /admin/v1/supervision`,
  `GET /admin/v1/supervision/alerts?since=` (historique, pour la console), `POST /admin/v1/supervision/{id}/suspend`,
  avec le jeton d'administration. Un site créé par `creche.sh creer` porte le slug de la crèche (libellé de son code
  d'activation) : la console rapproche ainsi supervision et hôte.

## Conséquences

- Le prestataire peut appeler la crèche avant qu'une panne ne coûte des données.
- Le service hébergé reçoit des métadonnées techniques de chaque crèche supervisée, et le contrat de support doit le
  mentionner. Ce ne sont pas des données personnelles au sens strict : aucune personne n'est identifiable.
- Un PC éteint le soir provoque une alerte « serveur muet » de nuit après 12 h : c'est voulu, car le serveur doit
  rester allumé pour la sauvegarde nocturne.
- Pas d'interface web d'administration : les e-mails et l'API d'administration suffisent au début.
