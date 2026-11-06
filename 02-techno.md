# Stack technique — Ti'Cagou

Application web hébergée, une instance par crèche ([ADR 0013](docs/adr/0013-web-heberge-instance-par-creche.md)).

```
 Crèche                                  Serveur hébergé (Docker)
 ┌───────────────────────────┐          ┌─────────────────────────────────────────┐
 │ PC bureau : navigateur    │  HTTPS   │ Caddy (HTTPS, sous-domaine par crèche)  │
 │ Tablette : PWA /kiosk,    │ ───────► │ Une instance par crèche : Java 21,      │
 │ file hors connexion       │          │ Spring Boot 3, documents chiffrés       │
 └───────────────────────────┘          │ PostgreSQL 17 : une base par crèche     │
                                        │ Portail parents et supervision          │
                                        └──────────────────┬──────────────────────┘
                                                           ▼
                                             Sauvegardes chez un second hébergeur
```

| Couche | Choix | Remarque |
|---|---|---|
| Bureau | React + TypeScript + Tailwind + shadcn/ui, dans le navigateur | Maquettes Claude Design réutilisables |
| Tablette pointage | Même front en mode « kiosque » (PWA) | Android ou iPad sans store ; boutons XXL ; hors connexion |
| Serveur | Java 21, Spring Boot 3, Flyway, Spring Security | |
| Base | PostgreSQL 17 | Une base et un rôle par crèche |
| PDF | Thymeleaf + openhtmltopdf | Factures, attestations, récap repas |
| Déploiement | Docker Compose, Caddy, `deploy/creche.sh` | Voir docs/EXPLOITATION.md |
| Portail parents | Service Spring Boot + application web mobile | Synchronisation depuis chaque instance, minimum de données |

## Points d'attention
- **Données sensibles** : rôles fins (une auxiliaire ne voit pas la facturation), journal d'accès aux dossiers enfants,
  sauvegardes chiffrées, export/suppression des données d'une famille à la sortie, double authentification.
- **Pointage = moment critique** : l'écran doit répondre instantanément et continuer si internet coupe.
- **Montants XPF** en entiers, règles (encadrement, aides, tarifs) en tables avec dates de validité.
- Fuseau `Pacific/Noumea`.
