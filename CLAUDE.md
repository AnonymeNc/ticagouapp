# CLAUDE.md — Ti'Cagou

## Produit
Logiciel de gestion pour crèches et garderies de **Nouvelle-Calédonie** : application web hébergée, une instance par
crèche ([ADR 0013](docs/adr/0013-web-heberge-instance-par-creche.md)). Le pointage sur tablette continue sans internet.
La V1 (client lourd) est archivée dans `ticagou-creche-v1-client-lourd`. MVP : enfants & familles, inscriptions, contrats,
pointage tablette, taux d'encadrement temps réel, repas, facturation mensuelle avec aides, personnel,
sauvegardes. V2 : portail parents en ligne optionnel.

## Architecture (monorepo)
- `server/` : Java 21, Spring Boot 3, Maven, PostgreSQL 17, Flyway, Spring Security. API REST `/api/v1`, SSE pour le temps réel.
- `web/` : React 19 + TypeScript + Vite, Tailwind + shadcn/ui, TanStack Query/Table, React Hook Form + Zod.
  Le même front expose une route `/kiosk` (pointage tablette, PWA). Servi par le serveur, même origine que l'API.
- `parents-portal/` : portail parents et supervision, hébergés.
- `deploy/` : image Docker, socle (PostgreSQL, Caddy), `creche.sh` pour exploiter les crèches.
- `docs/design/` : maquettes / handoff Claude Design (référence visuelle à respecter).
- `docs/adr/` : décisions d'architecture.

## Formule
Une seule formule, tout compris (ADR 0021) : aucune fonction n'est réservée à un abonnement. L'abonnement limite seulement
les places, les PC, les tablettes et l'échéance. « Module Repas », « module Personnel »… désignent des domaines fonctionnels.
À la demande d'une crèche, l'éditeur peut masquer des rubriques (ADR 0022) : simple affichage, rien n'est refusé ni effacé.

## Règles métier non négociables
- Montants XPF en `long`/`BIGINT`, aucune décimale ; jamais de `double` pour de l'argent.
- Ratios d'encadrement, barèmes, aides et financeurs : **tables paramétrables avec dates de validité**, jamais en dur.
- Une facture validée est immuable ; correction par avoir. Numérotation continue par structure et par exercice.
- Le départ d'un enfant ne peut être enregistré qu'avec une personne autorisée ; sinon blocage + validation direction tracée.
- Les informations de santé (allergies, PAI, régime) sont visibles partout où l'enfant apparaît (pointage, repas, fiche).
- Données de santé d'enfants : accès par rôle (DIRECTION, EDUCATRICE, CUISINE, COMPTABILITE), journal des consultations
  de dossiers, sauvegardes chiffrées, export et purge des données d'une famille, double authentification (direction,
  comptabilité), stockage de la tablette chiffré.
- Toute table métier porte `structure_id` (multi-structures).
- Fuseau `Pacific/Noumea`, interface en français, dates JJ/MM/AAAA.

## Conventions
- Back : packages par domaine (`child`, `family`, `enrollment`, `contract`, `attendance`, `staffing`, `meal`, `billing`,
  `funder`, `staff`, `backup`, `auth`), controller / service / repository, DTO séparés, Bean Validation, erreurs RFC 7807.
- Migrations Flyway uniquement, jamais `ddl-auto=update`.
- Front : un dossier par fonctionnalité dans `web/src/features/`, client API typé généré depuis l'OpenAPI.
- Tests : JUnit 5 + Testcontainers ; Vitest ; tout calcul (facturation, encadrement, aides) a des tests unitaires.

## Commandes
- Back : `cd server && ./mvnw verify` ; lancer : `./mvnw spring-boot:run`
- Front : `cd web && pnpm install && pnpm dev` ; kiosque : `/kiosk`
- Exploitation : `deploy/creche.sh`, voir docs/EXPLOITATION.md
- BDD de dev : `docker compose up -d postgres`

## Façon de travailler
- Respecter les maquettes de `docs/design/` (ou le lien Claude Design fourni) ; signaler tout écart.
- Plan court avant toute fonctionnalité non triviale ; tests lancés avant de déclarer une tâche terminée.
- Pas de dépendance lourde sans ADR.
