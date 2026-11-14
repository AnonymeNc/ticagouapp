# ADR 0001 — Stack technique et organisation du monorepo

- **Statut** : acceptée
- **Date** : 31/10/2026, mise à jour le 15/11/2026

## Contexte

Ti'Cagou est une application web hébergée, une instance par crèche ([ADR 0013](0013-web-heberge-instance-par-creche.md)),
utilisée depuis le navigateur du bureau, une tablette d'accueil qui pointe même sans internet, et trois applications
mobiles ([ADR 0023](0023-applications-mobiles-capacitor.md)). Les données traitées incluent des informations de santé
d'enfants.

## Décision

| Brique | Choix | Version retenue |
|---|---|---|
| `server/` | Java 21, Spring Boot 3, Maven (wrapper), Spring Web / Data JPA / Validation / Security / Actuator, Flyway, springdoc-openapi | Boot **3.5.16**, springdoc **2.10.0** |
| Base | PostgreSQL 17 : une base et un rôle par crèche (Docker) | 17 |
| `web/` | React 19 + TypeScript + Vite, Tailwind CSS 4 + shadcn/ui, TanStack Query, React Router, lucide-react | Vite 8, TS 6, Tailwind 4.3 |
| Kiosque | Route `/kiosk` du même front (PWA), aussi embarquée dans l'application Pointage | — |
| `parents-portal/` | Service Spring Boot + application des parents (React, PWA et application mobile) | [ADR 0010](0010-portail-parents-flux-de-donnees.md) |
| `console/` | Console de l'éditeur : Spring Boot + React | [ADR 0017](0017-console-editeur.md) |
| `mobile/` | Coques Capacitor 8 : Direction, Pointage, Parents | [ADR 0023](0023-applications-mobiles-capacitor.md) |
| `deploy/` | Image Docker, Caddy, `creche.sh` | [EXPLOITATION.md](../EXPLOITATION.md) |

Points structurants :

- **API REST versionnée** `/api/v1`, documentée par OpenAPI (`/v3/api-docs`), qui n'évolue que par ajouts
  ([ADR 0027](0027-distribution-et-mises-a-jour.md)). Erreurs au format RFC 7807 (`spring.mvc.problemdetails.enabled`).
- **Schéma piloté par Flyway** uniquement ; Hibernate en `ddl-auto=validate`.
- **Sécurité fermée par défaut** : seuls la santé (`/api/v1/health`), la compatibilité des applications
  (`/api/v1/app/compat`) et les pages publiques (pré-inscription) sont ouverts sans authentification.
- **Temps** : bean `Clock` sur `Pacific/Noumea` injecté partout ; JSON en `Pacific/Noumea` ; horodatages stockés en UTC.
- **Design system** : les tokens des maquettes `docs/design/` (couleurs, typo, rayons, ombres, dimensions) sont
  repris dans `web/src/index.css` (`@theme` de Tailwind 4). Les variables sémantiques shadcn (`--primary`, `--muted`…)
  sont branchées sur ces tokens.
- **Polices embarquées** : Source Sans 3 et Fraunces via `@fontsource-variable` (aucun appel à Google Fonts, aussi
  dans les applications).

## Alternatives écartées

- **Spring Boot 4** : la cible reste Boot 3 ; une migration fera l'objet d'un ADR dédié.
- **Applications natives séparées** (Swift, Kotlin) : trois bases de code à maintenir, voir l'ADR 0023.
- **SQLite embarqué** : insuffisant pour plusieurs postes simultanés et pour les sauvegardes chiffrées à chaud.

## Conséquences

- Outils de développement : JDK 21 (wrapper Maven), Node 22+ et pnpm, Docker Desktop ; Android Studio pour les
  applications Android, un Mac (ou la CI macOS) pour iOS.
- Les tests d'intégration serveur (`*IT`, Testcontainers) nécessitent Docker ; ils sont ignorés automatiquement si
  Docker est absent (`@Testcontainers(disabledWithoutDocker = true)`), les tests unitaires restent exécutés.
- Toute nouvelle dépendance lourde passe par un ADR.
