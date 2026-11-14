# Stack technique — Ti'Cagou

Application web hébergée, une instance par crèche ([ADR 0013](docs/adr/0013-web-heberge-instance-par-creche.md)),
et trois applications mobiles ([ADR 0023](docs/adr/0023-applications-mobiles-capacitor.md)).

```
 Crèche et familles                          Serveur hébergé (Docker)
 ┌─────────────────────────────────┐        ┌──────────────────────────────────────────┐
 │ PC bureau : navigateur          │        │ Caddy (HTTPS, sous-domaine par crèche)   │
 │ Tablette : /kiosk ou            │ HTTPS  │ Une instance par crèche : Java 21,       │
 │   Ti'Cagou Pointage (Android),  │ ─────► │   Spring Boot 3, documents chiffrés      │
 │   file hors connexion chiffrée  │        │ PostgreSQL 17 : une base par crèche      │
 │ Direction : Ti'Cagou Direction  │        │ Portail parents, supervision, annuaire,  │
 │ Parents : Ti'Cagou Parents      │        │   console de l'éditeur                   │
 └─────────────────────────────────┘        └───────────────┬──────────────────────────┘
          ▲ notifications                                   │
          └──── APNs (Apple), FCM (Google) ◄────────────────┤
                                                            ▼
                                              Sauvegardes chez un second hébergeur
```

| Couche | Choix | Remarque |
|---|---|---|
| Bureau | React + TypeScript + Tailwind + shadcn/ui, dans le navigateur | Maquettes de `docs/design/` |
| Tablette de pointage | Même front en mode « kiosque » (PWA), ou application Ti'Cagou Pointage (Android, mode kiosque) | Boutons XXL ; hors connexion |
| Applications mobiles | Capacitor 8 : Direction (iOS, Android), Pointage (Android), Parents (iOS, Android) | Même code React ; coffre du téléphone, Face ID, notifications |
| Serveur | Java 21, Spring Boot 3, Flyway, Spring Security | |
| Base | PostgreSQL 17 | Une base et un rôle par crèche |
| PDF | Thymeleaf + openhtmltopdf | Contrats, factures, attestations, récap repas |
| Déploiement | Docker Compose, Caddy, `deploy/creche.sh` | Voir docs/EXPLOITATION.md |
| Portail parents | Service Spring Boot + application des parents (web et mobile) | Synchronisation depuis chaque instance, minimum de données |
| Console de l'éditeur | Spring Boot + React | Parc, abonnements, factures, ordres |
| CI | GitHub Actions : tests, image, compilation Android et iOS (macOS) ; publication signée à la demande | `.github/workflows/` |

## Points d'attention
- **Données sensibles** : rôles fins (une auxiliaire ne voit pas la facturation), journal d'accès aux dossiers enfants,
  sauvegardes chiffrées, export/suppression des données d'une famille à la sortie, double authentification, aucune
  donnée de santé dans les notifications.
- **Pointage = moment critique** : l'écran doit répondre instantanément et continuer si internet coupe.
- **Montants XPF** en entiers, règles (encadrement, aides, tarifs) en tables avec dates de validité.
- **API stable** : `/api/v1` n'évolue que par ajouts, une application installée doit toujours comprendre le serveur
  ([ADR 0027](docs/adr/0027-distribution-et-mises-a-jour.md)).
- Fuseau `Pacific/Noumea`.
