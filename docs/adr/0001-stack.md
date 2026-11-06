# ADR 0001 — Stack technique et organisation du monorepo

- **Statut** : acceptée, modifiée par l'[ADR 0013](0013-web-heberge-instance-par-creche.md) (plus de client Tauri ni d'installeur)
- **Date** : 31/10/2026

## Contexte

Ti'Cagou est installé **dans** la crèche : il doit fonctionner sans internet, sur un réseau local, avec un
poste bureau Windows (direction) et une tablette d'accueil pour le pointage. Les données traitées incluent
des informations de santé d'enfants. Le même socle que le projet de gestion PME est souhaité pour mutualiser
compétences et composants (voir `02-techno.md`).

## Décision

Un monorepo à trois briques :

| Brique | Choix | Version retenue |
|---|---|---|
| `server/` | Java 21, Spring Boot 3, Maven (wrapper), Spring Web / Data JPA / Validation / Security / Actuator, Flyway, PostgreSQL, springdoc-openapi | Boot **3.5.16**, springdoc **2.10.0** |
| Base | PostgreSQL 17 (`docker compose` en dev, installation native en production) | 17 |
| `desktop/` | Tauri 2 + React 19 + TypeScript + Vite, Tailwind CSS 4 + shadcn/ui, TanStack Query, React Router, lucide-react | Vite 8, TS 6, Tailwind 4.3 |
| Kiosque | Route `/kiosk` du même front, servie en PWA par le serveur local | — |
| `installer/` | jpackage + WinSW + Inno Setup | phase 9 ([installer/README.md](../../installer/README.md)) |

Points structurants :

- **API REST versionnée** `/api/v1`, documentée par OpenAPI (`/v3/api-docs`) ; le client TypeScript sera
  généré depuis ce contrat. Erreurs au format RFC 7807 (`spring.mvc.problemdetails.enabled`).
- **Schéma piloté par Flyway** uniquement ; Hibernate en `ddl-auto=validate`.
- **Sécurité fermée par défaut** : seuls `GET /api/v1/health`, la santé Actuator et la doc OpenAPI sont publics.
  L'authentification (JWT + refresh, PIN tablette) arrive en phase 1.
- **Temps** : bean `Clock` sur `Pacific/Noumea` injecté partout ; JSON en `Pacific/Noumea` ; horodatages stockés en UTC.
- **Design system** : les tokens des maquettes `docs/design/` (couleurs, typo, rayons, ombres, dimensions) sont
  repris dans `desktop/src/index.css`. Avec Tailwind 4, la configuration du thème est en CSS (`@theme`) et non dans
  un `tailwind.config.js`. Les variables sémantiques shadcn (`--primary`, `--muted`…) sont branchées sur ces tokens.
- **Hors ligne** : polices Source Sans 3 et Fraunces embarquées via `@fontsource-variable` (aucun appel à Google Fonts).

## Alternatives écartées

- **Spring Boot 4** : disponible, mais la cible demandée est Boot 3 (écosystème et compétences du projet PME).
  La branche 3.5 reste à surveiller pour sa fin de support ; une migration fera l'objet d'un ADR dédié.
- **Electron** au lieu de Tauri : binaire bien plus lourd, consommation mémoire supérieure sur des PC de bureau modestes.
- **Application mobile native** pour la tablette : impose un store et des mises à jour ; une PWA servie par le serveur
  local suffit et fonctionne sur Android comme sur iPad.
- **SQLite embarqué** : insuffisant pour plusieurs postes simultanés (bureau + tablette) et pour les sauvegardes chiffrées
  à chaud.

## Conséquences

- Deux chaînes d'outils à installer en dev : JDK 21 + Maven wrapper, Node 22+ / pnpm + Rust (MSVC) pour Tauri.
- Les tests d'intégration serveur (`*IT`, Testcontainers) nécessitent Docker ; ils sont ignorés automatiquement
  si Docker est absent (`@Testcontainers(disabledWithoutDocker = true)`), les tests unitaires restent exécutés.
- Toute nouvelle dépendance lourde passe par un ADR.
