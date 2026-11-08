# Console de l'éditeur Ti'Cagou

Tableau de bord de l'éditeur : parc des crèches, facturation des abonnements, relances préparées (envoyées d'un clic),
ordres aux instances, reporting. Décision : [ADR 0017](../docs/adr/0017-console-editeur.md). Plan :
[plan-console-editeur.md](../docs/plan-console-editeur.md). Maquette : `docs/design/Ti'Cagou — Console éditeur.html`.

| Dossier | Contenu |
|---|---|
| `server/` | Spring Boot 3.5, Java 21, Spring Security, PostgreSQL 17, Flyway |
| `web/` | Interface React 19 + Vite + Tailwind, servie par `server/` en production |

**Livré (phase 1)** : premier compte par jeton, connexion par mot de passe et code TOTP obligatoire, blocage après
5 échecs, limite par adresse, CSRF, session de 8 heures au plus, code redemandé pour les actions sensibles
(`SensitiveAction`), journal (Paramètres › Journal). Les autres écrans annoncent leur phase.

## Lancer en développement

```bash
cd console && docker compose up -d
cd server
CONSOLE_SECRET_KEY=$(openssl rand -base64 32) CONSOLE_SETUP_TOKEN=dev CONSOLE_SECURE_COOKIE=false ./mvnw spring-boot:run
```

Gardez la même `CONSOLE_SECRET_KEY` d'un lancement à l'autre : elle chiffre les secrets TOTP en base.

Interface : `cd web && pnpm install && pnpm dev` (http://localhost:1421, l'API est relayée vers le port 8095).
Premier compte : http://localhost:1421/premier-compte?jeton=dev, puis connexion avec une application d'authentification.

## Tests

```bash
cd server && ./mvnw verify
cd web && pnpm test
```

## En production

`./creche.sh socle --console` (voir docs/EXPLOITATION.md, § 3 bis).
