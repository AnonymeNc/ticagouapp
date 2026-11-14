# ADR 0002 — Authentification, rôles et journal d'audit

- **Statut** : acceptée, complétée par l'[ADR 0024](0024-authentification-applications.md) (applications mobiles)
- **Date** : 31/10/2026, mise à jour le 15/11/2026

## Contexte

Chaque instance de crèche est utilisée par :

- le **navigateur du bureau** : direction et comptabilité, identifiant, mot de passe et double authentification ;
- la **tablette d'accueil** (kiosque) : éducatrices et cuisine, qui changent de main toute la journée, code PIN à
  4 chiffres ;
- les **applications mobiles** Direction et Pointage ([ADR 0024](0024-authentification-applications.md)).

Les données manipulées incluent des informations de santé d'enfants : accès par rôle, traçabilité des consultations,
aucune donnée sensible en clair.

## Décision

| Sujet | Choix |
|---|---|
| Jeton d'accès | JWT signé **HS256**, 15 min, émis et vérifié avec `spring-boot-starter-oauth2-resource-server` (Nimbus JOSE) |
| Clé de signature | `ticagou.security.jwt-secret` si fourni (≥ 32 octets, Base64) ; sinon générée au premier démarrage et conservée en base (`server_secret`) |
| Rafraîchissement | Jeton opaque aléatoire (256 bits), seul son SHA-256 est stocké ; **rotation à chaque usage**, réutilisation d'un jeton déjà utilisé ⇒ révocation de toute la famille |
| Navigateur | Jeton de rafraîchissement dans un cookie `HttpOnly`, `Secure`, `SameSite=Strict` ; jeton d'accès en mémoire |
| Double authentification | TOTP (RFC 6238, `auth/Totp`, JDK seul), obligatoire pour `DIRECTION` et `COMPTABILITE` ; tant qu'elle n'est pas enregistrée, `MfaGuard` refuse tout sauf `/api/v1/auth/**` |
| Mots de passe | **Argon2id** (`Argon2PasswordEncoder`) — nécessite **Bouncy Castle** (`bcprov-jdk18on`) |
| PIN tablette | 4 chiffres, haché Argon2id ; 5 échecs ⇒ blocage 15 min de ce PIN (mot de passe : 5 échecs ⇒ 15 min) ; connexions manquées limitées aussi par adresse (20 en 10 min) |
| Rôles | Un rôle par utilisateur : `DIRECTION`, `EDUCATRICE`, `CUISINE`, `COMPTABILITE` ; autorité `ROLE_<rôle>`, contrôles `@PreAuthorize` |
| Multi-structures | L'identifiant de structure (`sid`) est dans le JWT ; chaque requête est restreinte à cette structure |
| Audit | Table `audit_event` (connexions, échecs, refus d'accès, modifications de paramètres et d'utilisateurs) et `child_record_access` (consultation d'un dossier enfant) ; **ajout seul**, imposé par trigger PostgreSQL |
| Premier lancement | `POST /api/v1/setup` n'est accepté qu'une fois, avec le jeton à usage unique donné par `creche.sh creer` (`/setup?jeton=…`) ; sans jeton configuré (développement), depuis le poste local seulement |

## Alternatives écartées

- **Sessions serveur** : un état de plus à partager entre redémarrages ; le cookie `HttpOnly` porte déjà le seul
  secret durable du navigateur.
- **RS256 / paire de clés** : utile quand des tiers vérifient les jetons ; ici seule l'instance les émet et les vérifie.
- **jjwt**, **bibliothèque TOTP** : dépendances de plus alors que Spring Security et le JDK suffisent.
- **BCrypt** : acceptable, mais Argon2id est la recommandation actuelle (résistance aux GPU).

## Conséquences

- La tablette ne garde aucune session persistante (changement d'utilisateur fréquent) : un rechargement redemande le
  PIN. La liste des personnes à PIN n'est lisible qu'avec un jeton d'appareil ([ADR 0005](0005-tablettes-hors-connexion.md)).
- Téléphone d'authentification perdu : la direction réinitialise la double authentification d'une autre personne,
  l'éditeur celle de la direction.
- Les tables d'audit ne peuvent pas être modifiées ni supprimées par l'application ; une purge réglementaire passe par
  une procédure d'administration dédiée.
