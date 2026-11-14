# ADR 0013 — Application web hébergée, une instance par crèche

- **Statut** : acceptée, complétée par les ADR [0024](0024-authentification-applications.md) (applications mobiles :
  jeton porteur, CORS) et [0025](0025-choix-de-la-creche.md) (annuaire des crèches)
- **Date** : 05/11/2026, mise à jour le 15/11/2026

## Contexte

Une crèche n'a pas d'informaticien : elle ne doit avoir ni serveur à garder allumé, ni certificat à installer, ni
disque de sauvegarde à changer, ni clé de licence à coller. Le territoire compte une cinquantaine de structures. Les
données traitées incluent des informations de santé d'enfants.

## Décision

**Architecture**
- Une image Docker contient le serveur et le front. Chaque crèche a son conteneur, sa base PostgreSQL, son rôle, son
  sous-domaine (`<crèche>.<DOMAINE_BASE>`) et son dossier.
- Caddy termine le HTTPS (Let's Encrypt). Le navigateur et la tablette parlent à la même origine ; les applications
  mobiles appellent l'adresse de la crèche choisie ([ADR 0024](0024-authentification-applications.md)).
- `deploy/creche.sh` crée, met à jour, suspend, restaure et supprime les crèches ; la console de l'éditeur
  ([ADR 0017](0017-console-editeur.md)) le pilote.
- Pas de SaaS mutualisé : une instance consomme environ 400 Mo, et l'isolation vient de la base et du rôle, pas d'une
  discipline du code. Le schéma garde `structure_id` : le passage à un service mutualisé reste possible.
- Le portail parents ([ADR 0010](0010-portail-parents-flux-de-donnees.md)), la supervision
  ([ADR 0012](0012-supervision-a-distance.md)), la console et l'annuaire des crèches partagent le même socle.

**Démarrage d'une crèche**
- `creche.sh creer` génère les secrets (`creche.env`) et un jeton à usage unique. La directrice ouvre `/setup?jeton=…`.
- Sans jeton configuré (développement), l'assistant n'accepte que le poste local.

**Abonnement** : une ligne dans la table `subscription`, écrite par l'éditeur, avec effet immédiat ; sans ligne,
évaluation ([ADR 0011](0011-licence-lecture-seule-postes.md)).

**Sauvegardes** : chiffrées, chaque nuit, copiées hors site, clé de secours hors de la base
([ADR 0007](0007-sauvegardes-chiffrees.md)). La direction voit l'état, lance une sauvegarde et un test de restauration ;
elle ne règle rien.

**Sécurité pour internet**
- Double authentification TOTP obligatoire pour la direction et la comptabilité ; cookie de session `HttpOnly`,
  `Secure`, `SameSite=Strict` ; connexions manquées limitées par compte et par adresse ([ADR 0002](0002-authentification.md)).
- CSP, HSTS et blocage de `/actuator`, Swagger et `/admin` dans Caddy.
- File de la tablette et instantané du jour chiffrés ([ADR 0005](0005-tablettes-hors-connexion.md)).

## Alternatives écartées

- **Serveur installé dans chaque crèche** : un PC à maintenir, des certificats sur chaque tablette, des sauvegardes
  dépendantes de la directrice, des déplacements pour chaque mise à jour.
- **SaaS mutualisé** : 20 à 30 jours de plus, et un oubli de filtre exposerait une autre crèche. Inutile à cette échelle.
- **Hybride local et cloud** : les inconvénients des deux, et des conflits de données.

## Conséquences

- Internet est obligatoire pour le bureau. Le pointage continue hors connexion, avec un taux d'encadrement incomplet
  ([ADR 0005](0005-tablettes-hors-connexion.md)).
- L'éditeur héberge des données de santé d'enfants. **La question de l'hébergement des données de santé n'est pas
  tranchée** : l'article L. 1111-8 applicable en Nouvelle-Calédonie renvoie à la réglementation locale. Un avis
  juridique décide du lieu d'hébergement. Le code ne dépend d'aucun hébergeur.
- Une panne du serveur touche toutes les crèches : voir [EXPLOITATION.md](../EXPLOITATION.md) (§ 9, perte totale).
- `creche.env` contient les clés d'une crèche : le perdre rend ses archives illisibles. Il va au coffre à chaque création.
