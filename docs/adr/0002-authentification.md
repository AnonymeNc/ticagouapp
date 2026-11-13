# ADR 0002 — Authentification, rôles et journal d'audit

- **Statut** : acceptée, complétée par l'[ADR 0013](0013-web-heberge-instance-par-creche.md) (double authentification, cookie de session, assistant par jeton) et par l'[ADR 0024](0024-authentification-applications.md) (jeton de rafraîchissement des applications mobiles dans le trousseau)
- **Date** : 31/10/2026

## Contexte

Le serveur local est utilisé par deux types de clients :

- le **PC du bureau** (client Tauri) : direction et comptabilité, identifiant + mot de passe ;
- la **tablette d'accueil** (kiosque) : éducatrices et cuisine, qui changent de main toute la journée,
  code PIN à 4 chiffres (maquette Paramètres › Utilisateurs et rôles).

Les données manipulées incluent des informations de santé d'enfants : accès par rôle, traçabilité des
consultations, aucune donnée sensible en clair.

## Décision

| Sujet | Choix |
|---|---|
| Jeton d'accès | JWT signé **HS256**, 15 min, émis et vérifié avec `spring-boot-starter-oauth2-resource-server` (Nimbus JOSE) |
| Clé de signature | `ticagou.security.jwt-secret` si fourni (≥ 32 octets, Base64) ; sinon générée au premier démarrage et conservée en base (`server_secret`) |
| Rafraîchissement | Jeton opaque aléatoire (256 bits), seul son SHA-256 est stocké ; **rotation à chaque usage**, réutilisation d'un jeton déjà utilisé ⇒ révocation de toute la famille |
| Mots de passe | **Argon2id** (`Argon2PasswordEncoder`, paramètres Spring Security 5.8) — nécessite **BouncyCastle** (`bcprov-jdk18on`) |
| PIN tablette | 4 chiffres, haché Argon2id ; 5 échecs ⇒ blocage 15 min de ce PIN (mot de passe : 5 échecs ⇒ 15 min) |
| Rôles | Un rôle par utilisateur : `DIRECTION`, `EDUCATRICE`, `CUISINE`, `COMPTABILITE` ; autorité `ROLE_<rôle>`, contrôles `@PreAuthorize` |
| Multi-structures | L'identifiant de structure (`sid`) est dans le JWT ; chaque requête est restreinte à cette structure |
| Audit | Table `audit_event` (connexions, échecs, refus d'accès, modifications de paramètres et d'utilisateurs) et `child_record_access` (consultation d'un dossier enfant) ; **ajout seul**, imposé par trigger PostgreSQL |
| Premier lancement | `POST /api/v1/setup` n'est accepté qu'une fois, et **uniquement depuis le PC serveur** (adresse de bouclage) |

Dépendances ajoutées : `spring-boot-starter-oauth2-resource-server` (géré par Spring Boot) et
`org.bouncycastle:bcprov-jdk18on`. Côté front : `react-hook-form`, `zod`, `@hookform/resolvers`
(déjà prévus par CLAUDE.md).

## Alternatives écartées

- **Sessions serveur + cookie** : le client Tauri et la tablette PWA n'ont pas la même origine que le serveur ;
  CORS + cookies tiers + CSRF compliquent tout pour peu de bénéfice sur un réseau local.
- **RS256 / paire de clés** : utile quand des tiers vérifient les jetons ; ici seul le serveur les émet et les vérifie.
- **jjwt** : bibliothèque de plus à maintenir alors que Spring Security fournit déjà Nimbus.
- **BCrypt** : acceptable, mais Argon2id est la recommandation actuelle (résistance aux GPU) et demandé par le cahier des charges.

## Conséquences

- Le client garde le jeton d'accès en mémoire et le jeton de rafraîchissement dans le stockage local du poste
  (Tauri) ; la tablette ne garde aucune session persistante (changement d'utilisateur fréquent).
  Un stockage sécurisé (trousseau système) pourra remplacer le stockage local dans un ADR ultérieur.
- La liste des utilisateurs à PIN (prénom + initiale) est lisible sans authentification pour afficher le
  sélecteur de la tablette ; elle sera réservée aux tablettes appairées lorsque l'appairage existera.
- Le journal des consultations référence `child_id` sans clé étrangère tant que la table `child` n'existe pas
  (phase 2). La purge des données d'une famille devra statuer sur la conservation de ces traces.
- Les tables d'audit ne peuvent pas être modifiées ni supprimées par l'application ; une purge réglementaire
  passera par une procédure d'administration dédiée.
