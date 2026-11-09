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
(`SensitiveAction`), journal (Paramètres › Journal).

**Livré (phase 2)** : parc des crèches (`parc/`) — supervision lue dans le portail toutes les 5 minutes
(`SupervisionCollector`), rapport de l'hôte reçu sur `POST /interne/v1/hote` (`HostReports`, `creche.sh rapport-console`),
rapprochés par le slug ; pastille de santé (`Health`), instantanés réduits à un par jour après 30 jours (`Snapshots`),
historique des alertes ; fiche crèche (santé, abonnement, usage, client et contacts, notes et appels, journal),
prospects, recherche, tableau de bord « À faire aujourd'hui ».

**Livré (phase 3)** : facturation de l'éditeur (`billing/`) — barème versionné et TGC datée (`CatalogueService`), calcul
des prix, remises et prorata (`Pricing`, `Totals`, `Xpf`, testés seuls), contrats et avenants, renouvellements préparés en
brouillon (`ContractService`), validation avec code (numéro continu, PDF `templates/facture.html` et son empreinte,
`InvoiceService`), avoirs, paiements (`PaymentService`), boîte d'envoi (`OutboxService`, `Mailer` : envoi au clic),
indicateurs (`BillingStats`). Une facture validée est protégée par un trigger (`V3__facturation.sql`).

**Livré (phase 4)** : relances (`ReminderPlanner` pour le calendrier, testé seul ; `ReminderService` pour la préparation de
6 h, regroupée par client ; `ReminderRepository`) ; règles datées, fériés et règles par client dans Paramètres ; au clic,
une facture soldée entre-temps retire la relance (`OutboxService`). Les autres écrans annoncent leur phase.

## Lancer en développement

```bash
cd console && docker compose up -d
cd server
CONSOLE_SECRET_KEY=$(openssl rand -base64 32) CONSOLE_SETUP_TOKEN=dev CONSOLE_SECURE_COOKIE=false ./mvnw spring-boot:run
```

Gardez la même `CONSOLE_SECRET_KEY` d'un lancement à l'autre : elle chiffre les secrets TOTP en base.

Pour remplir le parc en développement : `CONSOLE_PORTAL_URL=http://localhost:8090` et
`CONSOLE_PORTAL_ADMIN_TOKEN=<jeton du portail>` (portail lancé à part), et `CONSOLE_HOST_TOKEN=dev` pour envoyer un rapport
d'hôte à la main (`curl -H "Authorization: Bearer dev" -H "Content-Type: application/json" --data-binary @hote.json
http://localhost:8095/interne/v1/hote`, au format de `creche.sh etat --json`).

Pour essayer les envois : `CONSOLE_MAIL_FROM="Ti'Cagou <facturation@ticagou.nc>"` et un SMTP de développement sur
`localhost:1025` (le Mailpit de `parents-portal/docker-compose.yml` convient, interface sur http://localhost:8025).

Interface : `cd web && pnpm install && pnpm dev` (http://localhost:1421, l'API est relayée vers le port 8095).
Premier compte : http://localhost:1421/premier-compte?jeton=dev, puis connexion avec une application d'authentification.

## Tests

```bash
cd server && ./mvnw verify
cd web && pnpm test
```

## En production

`./creche.sh socle --console` (voir docs/EXPLOITATION.md, § 3 bis).
