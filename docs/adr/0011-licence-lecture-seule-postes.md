# ADR 0011 — Abonnement : évaluation, grâce, lecture seule et postes comptés

- **Statut** : acceptée, complétée par l'[ADR 0028](0028-postes-mobiles-et-abonnement.md) (téléphones de la direction)
- **Date** : 03/11/2026, mise à jour le 15/11/2026

## Contexte

L'abonnement d'une crèche ([ADR 0021](0021-formule-unique.md) : une seule formule, tout compris) doit avoir un effet,
sans mettre en danger les données ni les enfants. Il limite les places, les PC du bureau, les tablettes et l'échéance.

## Décision

**Abonnement** : une ligne dans la table `subscription` de l'instance (places, PC, tablettes, échéance), écrite par
l'éditeur (`creche.sh abonnement` ou la console, [ADR 0017](0017-console-editeur.md)), avec effet immédiat. Sans ligne,
l'instance est en évaluation.

**Dates** (`license/LicenseTerms`, règles sans base de données, testées seules)
- **Évaluation** : 30 jours depuis l'installation (`installation.initialized_at`), puis **lecture seule**.
- **Abonnement** : bandeau d'information 30 jours avant l'échéance. Après l'échéance, **30 jours de grâce** : tout
  fonctionne sauf l'appairage de tablettes, avec un bandeau orange puis rouge. Ensuite, lecture seule.
- Les durées sont des réglages (`ticagou.license.evaluation-days`, `grace-days`) et non des constantes.
- **Horloge reculée** : l'instance retient le plus grand jour qu'elle a vu (`installation.last_seen_on`).

**Lecture seule** (`license/LicenseGuard`, intercepteur de `/api/**`)
- Toute requête de modification (autre que GET, HEAD, OPTIONS) est refusée : 403 `license_read_only`, avec le message
  de l'abonnement.
- **Restent possibles** : la connexion (mot de passe, PIN, renouvellement de session, session des tablettes),
  l'assistant d'installation, les sauvegardes (manuelle et test de restauration), toute lecture, dont l'export des
  données d'une famille. La sauvegarde nocturne continue : elle ne passe pas par l'API.
- **Tablettes** : le pointage est refusé comme toute modification. La tablette affiche un bandeau rouge : noter les
  arrivées et départs sur papier. La liste des personnes autorisées reste consultable.

**Postes** (`license/DesktopClientService`, table `desktop_client`)
- Chaque navigateur du bureau tire au hasard un identifiant à sa première ouverture, le garde, et l'envoie dans
  l'en-tête `X-Ticagou-Poste` avec un nom (`X-Ticagou-Poste-Nom`). L'instance n'en garde que le haché.
- À la connexion par mot de passe et au renouvellement de session, le poste est enregistré.
  - Au-delà des PC de l'abonnement (2 en évaluation), un nouveau PC est refusé : 422 `license_desktops`.
  - Un poste absent depuis 90 jours (`desktop-idle`) libère sa place.
  - La direction peut retirer un poste (Paramètres › Tablettes et abonnement) : il ne peut plus se connecter, et sa
    session tombe au prochain renouvellement.
- Les téléphones de la direction sont comptés à part, plafond 10 ([ADR 0028](0028-postes-mobiles-et-abonnement.md)).

## Conséquences

- Une crèche qui ne renouvelle pas garde l'accès à toutes ses données et à ses sauvegardes, mais ne peut plus rien
  saisir. Le renouvellement par l'éditeur débloque tout immédiatement.
- Les bases de démonstration et de développement reçoivent un abonnement d'un an (`scripts/tester-en-local.ps1`) ;
  l'évaluation reste de 30 jours, comme en production.
- La lecture seule bloque aussi le pointage des tablettes : la traçabilité des départs repasse sur papier. **Choix à
  confirmer avant la mise en service** ; à revoir si une crèche le conteste.
