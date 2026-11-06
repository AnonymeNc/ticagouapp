# ADR 0013 — Application web hébergée, une instance par crèche

- **Statut** : acceptée
- **Date** : 05/11/2026
- **Remplace** : ADR 0009 (HTTPS local, mDNS). **Modifie** : ADR 0001, 0002, 0005, 0007, 0011, 0012.

## Contexte

La V1 installe un serveur Windows dans chaque crèche, avec un client Tauri, un certificat sur chaque tablette, un disque
de sauvegarde et une clé de licence à coller. Chaque crèche devient un petit centre informatique, sans informaticien.
L'étude « Ticagou : passer du client lourd à l'application web ? » conclut que le client lourd n'apporte presque rien
(364 lignes de Rust pour 25 800 lignes de React) et que la vraie question est l'emplacement du serveur.

L'option B est retenue : une instance par crèche sur un serveur hébergé. Le projet repart dans un dépôt neuf,
`ticagou-web`. La V1 est conservée telle quelle dans `ticagou-creche-v1-client-lourd`. Aucune crèche n'est installée :
il n'y a pas de données à migrer.

## Décision

**Architecture**
- Une image Docker contient le serveur et le front. Chaque crèche a son conteneur, sa base PostgreSQL, son rôle, son
  sous-domaine et son dossier.
- Caddy termine le HTTPS (Let's Encrypt). Le navigateur et la tablette parlent à la même origine.
- `deploy/creche.sh` crée, met à jour, suspend, restaure et supprime les crèches.
- Pas de SaaS mutualisé : le territoire compte une cinquantaine de structures, et une instance consomme environ 400 Mo.
  L'isolation vient de la base et du rôle, pas d'une discipline du code. Le schéma garde `structure_id` : le passage à
  un service mutualisé reste possible.

**Retiré de la V1** : client Tauri et Rust, installeur Windows, autorité de certification locale, mDNS, mises à jour
par MSI, clés de licence Ed25519, dossiers de sauvegarde réglés par la direction, fiche de secours remise à la directrice.

**Démarrage d'une crèche**
- `creche.sh creer` génère les secrets et un jeton à usage unique. La directrice ouvre `/setup?jeton=…`.
- Sans jeton configuré (développement), l'assistant n'accepte que le poste local. Le contrôle ne repose plus sur
  l'adresse en production : derrière un proxy, toutes les requêtes paraîtraient locales.

**Abonnement** (remplace la clé de licence)
- Une ligne dans la table `subscription`, écrite par `creche.sh abonnement`, avec effet immédiat. Sans ligne : évaluation.
- Les règles de l'ADR 0011 restent : 30 jours d'évaluation, 30 jours de grâce, lecture seule, PC et tablettes comptés.
- Le pointage reste refusé en lecture seule, comme décidé à l'ADR 0011. **Décision à reprendre avant la mise en service.**

**Sauvegardes**
- Dossier (`/data/backups`) et heure viennent de la configuration de l'instance. L'heure est tirée entre 1 h et 5 h.
- Clé de secours générée par `creche.sh`, hors de la base. Elle est gardée dans `creche.env` et dans le coffre de
  l'éditeur : une copie des archives ne contient plus la clé.
- `sauvegarde-hors-site.sh` copie les archives chez un second hébergeur, sans jamais effacer.
- La direction voit l'état, lance une sauvegarde et un test de restauration. Elle ne règle plus rien.

**Sécurité pour internet**
- Double authentification TOTP, sans dépendance (`auth/Totp`), obligatoire pour la direction et la comptabilité. Tant
  qu'elle n'est pas enregistrée, `MfaGuard` refuse tout sauf `/api/v1/auth/**`. La tablette (PIN) n'est pas concernée.
- Téléphone perdu : la direction réinitialise pour une autre personne, l'éditeur pour la direction.
- Jeton de rafraîchissement du navigateur dans un cookie `HttpOnly`, `Secure`, `SameSite=Strict`. La tablette garde sa
  session en mémoire.
- Connexions manquées limitées par adresse (20 en 10 minutes), en plus du blocage par compte.
- CSP, HSTS et blocage de `/actuator`, Swagger et `/admin` dans Caddy.
- File de la tablette et instantané du jour chiffrés (AES-256-GCM, clé non extractible, `kiosk/secureStore`). Sans
  stockage disponible, ils restent en mémoire, jamais en clair sur le disque.

**Supervision** : activée d'office par `creche.sh` quand le portail tourne ; elle n'est plus un choix de la directrice.
L'écran Paramètres › Supervision disparaît. Les indicateurs restent ceux de l'ADR 0012.

**Portail parents** : inchangé (ADR 0010). La synchronisation part de chaque instance. Le portail rejoint le même socle.

## Alternatives écartées

- **Rester en local, avec sauvegarde et licence en ligne** : garde les déplacements et le PC à maintenir.
- **SaaS mutualisé** : 20 à 30 jours de plus, et un oubli de filtre exposerait une autre crèche. Inutile à cette échelle.
- **Hybride local et cloud** : les inconvénients des deux, et des conflits de données.
- **Bibliothèque TOTP** : une centaine de lignes du JDK suffisent, vérifiées sur les vecteurs de la RFC 6238.

## Conséquences

- Internet devient obligatoire pour le bureau. Le pointage continue hors connexion, mais :
  - une tablette ne voit que ses propres pointages hors connexion, et le taux d'encadrement est incomplet ;
  - si la coupure commence avant l'ouverture, la tablette n'a pas l'instantané du jour.
- L'éditeur devient hébergeur de données de santé d'enfants. **La question de l'hébergement des données de santé n'est
  pas tranchée** : l'article L. 1111-8 applicable en Nouvelle-Calédonie renvoie à la réglementation locale. Un avis
  juridique décide du lieu d'hébergement. Le code ne dépend d'aucun hébergeur.
- Une panne du serveur touche toutes les crèches : voir docs/EXPLOITATION.md.
- `creche.env` contient les clés d'une crèche : le perdre rend ses archives illisibles. Il va au coffre à chaque création.
- Le secret de la tablette reste dans son stockage local : il donne accès au pointage, pas aux données d'une autre crèche.
