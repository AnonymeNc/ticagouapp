# ADR 0011 — Licence : évaluation, grâce, lecture seule et PC comptés

- **Statut** : acceptée, modifiée par l'[ADR 0013](0013-web-heberge-instance-par-creche.md) (abonnement au lieu de la clé signée ; les règles de dates restent)
- **Date** : 03/11/2026

## Contexte

Jusqu'ici, la licence (clé signée Ed25519, vérifiée hors ligne, `license/LicenseCodec`) ne limitait presque rien :
- l'évaluation sans clé n'avait pas de fin ;
- une licence expirée ne bloquait que l'appairage de nouvelles tablettes ;
- le nombre de PC du bureau inscrit dans la clé n'était jamais contrôlé ;
- la clé publique livrée était celle de développement, dont la clé privée est dans le dépôt.

Avant de vendre, la licence doit avoir un effet, sans mettre en danger les données ni les enfants.

## Décision

**Dates** (`license/LicenseTerms`, règles sans base de données, testées seules)
- **Évaluation** : 30 jours depuis l'installation (`installation.initialized_at`), puis **lecture seule**.
- **Licence** : à renouveler 30 jours avant l'échéance (bandeau d'information). Après l'échéance, **30 jours de
  grâce** : tout fonctionne sauf l'appairage de tablettes, avec un bandeau orange puis rouge. Ensuite, lecture seule.
- Les durées sont des réglages (`ticagou.license.evaluation-days`, `grace-days`) et non des constantes.
- **Horloge reculée** : le serveur retient le plus grand jour qu'il a vu (`installation.last_seen_on`). Remettre le
  PC à une date antérieure ne rend ni jour d'évaluation ni jour de grâce.

**Lecture seule** (`license/ModuleGuard`, intercepteur de `/api/**`)
- Toute requête de modification (autre que GET, HEAD, OPTIONS) est refusée : 403 `license_read_only`, avec le
  message de la licence.
- **Restent possibles** :
  - la connexion (mot de passe, PIN, renouvellement de session, session des tablettes) ;
  - l'assistant d'installation ;
  - l'activation d'une nouvelle clé ;
  - les sauvegardes (manuelle et test de restauration) ;
  - toute lecture, dont l'export des données d'une famille.
- La sauvegarde nocturne continue : elle ne passe pas par l'API.
- **Tablettes** : le pointage est refusé comme toute modification, à la demande de la direction du projet. La
  tablette affiche un bandeau rouge : noter les arrivées et départs sur papier. La liste des personnes autorisées
  reste consultable, car elle est en lecture et la connexion par PIN reste permise.

**PC du bureau** (`license/DesktopClientService`, table `desktop_client`)
- Chaque client (bureau Tauri ou navigateur) tire au hasard un identifiant à sa première ouverture, le garde, et
  l'envoie dans l'en-tête `X-Ticagou-Poste` avec le nom du PC (`X-Ticagou-Poste-Nom`). Le serveur n'en garde que le
  haché.
- À la connexion par mot de passe et au renouvellement de session, le PC est enregistré.
  - Au-delà des PC de la licence (2 en évaluation), un nouveau PC est refusé : 422 `license_desktops`.
  - Un PC absent depuis 90 jours (`desktop-idle`) libère sa place.
  - La direction peut retirer un PC (Paramètres › Tablettes, licence) : il ne peut plus se connecter, et sa session
    tombe au prochain renouvellement.
- Les anciens clients sans en-tête partagent une seule place : une mise à jour du serveur ne bloque pas un PC dont
  le client n'est pas encore à jour.

**Clé de production**
- `installer/build.ps1` exige `-LicencePublicKey` (fichier `public.key` de `licence.ps1 paire`). Il l'écrit dans les
  ressources le temps de construire le jar.
- Sans cette clé, la construction s'arrête, sauf avec `-Dev`. Le setup s'appelle alors `…-DEV.exe` et ne doit pas
  être livré.
- Un serveur qui porte la clé de développement l'écrit dans son journal et l'affiche dans Paramètres.

## Conséquences

- Une crèche qui ne renouvelle pas garde l'accès à toutes ses données et à ses sauvegardes, mais ne peut plus rien
  saisir. Le support doit pouvoir envoyer une clé très vite : c'est l'activation d'une clé qui débloque tout, hors
  ligne.
- Les bases de démonstration et de développement, installées il y a longtemps, passeraient en lecture seule :
  `scripts/tester-en-local.ps1` lance le serveur avec `TICAGOU_EVALUATION_DAYS=3650`.
- La lecture seule bloque aussi le pointage des tablettes : la traçabilité des départs repasse sur papier. Ce choix
  est à revoir si une crèche le conteste.
- Contourner la licence reste possible pour qui modifie le jar ou la base : la clé protège contre l'oubli et
  l'abus ordinaire, pas contre une attaque délibérée sur le PC de la crèche.
