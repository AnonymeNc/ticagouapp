# ADR 0012 — Supervision à distance des instances de crèche

- **Statut** : acceptée
- **Date** : 03/11/2026, mise à jour le 15/11/2026

## Contexte

Chaque crèche a son instance hébergée ([ADR 0013](0013-web-heberge-instance-par-creche.md)). Une sauvegarde qui ne se
fait plus, un disque plein, un abonnement qui expire ou une tablette muette doivent être vus par l'éditeur avant la
directrice, sans accéder aux données de la crèche.

## Décision

- **Activée d'office** par `creche.sh creer` quand le portail tourne ; ce n'est pas un réglage de la directrice.
- **Même service hébergé que le portail parents, mais indépendant** :
  - paire Ed25519 distincte (`server_secret` `monitor-key-…`) ;
  - tables distinctes (`supervision_site`, `supervision_alert`) ;
  - une crèche sans portail parents peut être supervisée.
- **Sens des connexions** : comme pour le portail (ADR 0010), l'instance ouvre toujours la connexion.
  `POST /supervision/v1/status` est signé comme la synchronisation : en-têtes `X-Ticagou-*`, 5 minutes d'écart au
  plus, rejeu refusé. Le service n'appelle jamais l'instance.
- **Contenu de l'état** (`monitoring/MonitoringStatus`, liste blanche ; le service ne garde que ces champs) :
  - version, état de la base ;
  - sauvegardes : activées, outils présents, retard de plus de 48 h, dernière réussite, dernier test de
    restauration ;
  - espace libre et total du disque des données et de celui des sauvegardes ;
  - abonnement : état, échéance, date de lecture seule, PC utilisés et permis ;
  - tablettes appairées et muettes depuis 3 jours ;
  - portail parents : état, échecs à la suite, dernière erreur, file d'attente ;
  - nombre de pointages à vérifier ;
  - usage, pour la console de l'éditeur ([ADR 0017](0017-console-editeur.md)) : enfants
    actifs, places du contrat, utilisateurs actifs sur 30 jours, jour de la dernière connexion d'un compte de direction,
    pointages des 7 derniers jours, factures aux familles du mois dernier. Des nombres et une date, rien d'autre.

  Aucun nom, aucune donnée d'enfant, de famille ou du personnel : un test le vérifie, et un autre vérifie que l'usage
  ne contient que des nombres et une date.
  - groupe, **seulement si la direction a
    rejoint un groupe** dans « Vue groupe », avec son consentement et un code commun dont seule l'empreinte sort : nom et
    adresse de la crèche, enfants inscrits, places, occupation des 30 derniers jours, montant facturé aux familles le
    mois dernier, reste dû, dépassements d'encadrement des 7 derniers jours. Le service ne garde que ces champs et ne les
    montre qu'aux crèches du même groupe (`GET /supervision/v1/group`, signé) ; quitter le groupe les efface au prochain
    état. Toujours rien sur les personnes.
- **Rythme** : toutes les 15 minutes, puis attente doublée après chaque échec, jusqu'à 1 h.
- **Alertes** (`supervision/AlertRules`, évaluées à la réception et toutes les 15 minutes) :
  - instance muette depuis 2 h entre 6 h et 19 h (heure de Nouméa), 12 h la nuit ;
  - sauvegarde en retard ;
  - disque à moins de 10 % ou de 5 Go libres ;
  - abonnement à renouveler ou expiré ;
  - tablette muette ;
  - portail en échec.

  Un e-mail part à l'ouverture et à la résolution de chaque alerte (une seule alerte ouverte par type et par
  site), plus un récapitulatif à 7 h. Le destinataire est l'éditeur (`PORTAL_SUPERVISION_EMAIL`), pas la
  crèche.
- **Hébergeur** : `POST /admin/v1/supervision-codes`, `GET /admin/v1/supervision`,
  `GET /admin/v1/supervision/alerts?since=` (historique, pour la console), `POST /admin/v1/supervision/{id}/suspend`,
  avec le jeton d'administration. Un site créé par `creche.sh creer` porte le slug de la crèche (libellé de son code
  d'activation) : la console rapproche ainsi supervision et hôte.

## Conséquences

- L'éditeur peut agir avant qu'une panne ne coûte des données.
- Le service hébergé reçoit des métadonnées techniques de chaque crèche supervisée, et le contrat de support doit le
  mentionner. Ce ne sont pas des données personnelles au sens strict : aucune personne n'est identifiable.
- Les alertes et l'historique se consultent aussi dans la console de l'éditeur ([CONSOLE.md](../CONSOLE.md)).
