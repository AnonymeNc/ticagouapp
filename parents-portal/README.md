# Portail parents Ti'Cagou

Service hébergé optionnel (ADR 0010). Il ne reçoit que ce que chaque crèche partage, par une synchronisation
**sortante** de son instance, et lui transmet les absences, messages et signatures des parents. Les seules données de
santé qui y arrivent sont une information que la direction a choisi de partager et les médicaments donnés à l'enfant
(ADR 0014).

| Dossier | Contenu |
|---|---|
| `server/` | Spring Boot 3.5, Java 21, PostgreSQL 17, Flyway : synchronisation signée, connexion des parents, API `/api/v1/me` |
| `web/` | Application web mobile (PWA) des parents, servie par `server/` |
| `contract/` | Exemples JSON du protocole, lus par les tests des deux côtés |

## Lancer en développement

```bash
cd parents-portal && docker compose up -d
cd server && ./mvnw spring-boot:run
```

Application des parents en développement : `cd web && pnpm install && pnpm dev` (http://localhost:5180, l'API est
relayée vers le portail). En production, `pnpm build` dans `web/` avant `./mvnw package` : la PWA est alors servie par
le portail lui-même.

Le portail écoute sur http://localhost:8090. Les e-mails de connexion arrivent dans Mailpit (http://localhost:8025).
Variables utiles en développement : `PORTAL_SECURE_COOKIE=false` (cookie sans HTTPS), `PORTAL_ADMIN_TOKEN=dev`.

Pour relier un serveur de crèche de développement :
1. créer un code d'activation :
   `curl -X POST -H "Authorization: Bearer dev" -H "Content-Type: application/json" -d '{"label":"Crèche de test"}' http://localhost:8090/admin/v1/activation-codes` ;
2. dans le logiciel, Paramètres › Portail parents : adresse `http://localhost:8090` (HTTP accepté pour `localhost` seulement)
   et le code ;
3. inviter un responsable depuis sa fiche famille ; il se connecte avec son adresse e-mail.

## Variables d'exploitation

| Variable | Rôle |
|---|---|
| `PORTAL_DB_URL`, `PORTAL_DB_USER`, `PORTAL_DB_PASSWORD` | Base PostgreSQL 17 |
| `PORTAL_PUBLIC_URL` | Adresse publique (liens des e-mails), en HTTPS |
| `PORTAL_FILE_KEY` | Clé maîtresse de chiffrement des fichiers, 32 octets en Base64 (`openssl rand -base64 32`). **À sauvegarder à part** : sans elle, photos et PDF sont illisibles |
| `PORTAL_ADMIN_TOKEN` | Jeton de l'hébergeur pour `/admin/v1` (codes d'activation, suspension) |
| `PORTAL_SUPERVISION_EMAIL` | Adresse qui reçoit les alertes de supervision des serveurs de crèche (ADR 0012) ; vide : journal seulement |
| `PORTAL_SMTP_HOST`, `PORTAL_SMTP_PORT`, `PORTAL_SMTP_USER`, `PORTAL_SMTP_PASSWORD`, `PORTAL_SMTP_AUTH`, `PORTAL_SMTP_STARTTLS`, `PORTAL_MAIL_FROM` | Envoi des e-mails de connexion |
| `PORTAL_FORWARD_HEADERS` | `native` derrière le proxy TLS (adresse IP du client pour la signature et les limites) |

Le portail doit être servi en HTTPS (proxy ou répartiteur de l'hébergeur) ; le cookie de session est `Secure`,
`HttpOnly`, `SameSite=Strict`.

## Conservation et effacement

- Tâche quotidienne (02:15, heure de Nouméa) : transmissions, présences et photos après 60 jours, factures et documents
  après 24 mois, messages après 12 mois ; demandes de connexion et sessions expirées.
- Une crèche qui retire l'accès d'une famille, la supprime ou désactive le portail envoie un ordre d'effacement
  immédiat (`PURGE_FAMILY`, `PURGE_STRUCTURE`).
- Sauvegarde de la base : chiffrée, conservée 30 jours au plus, pour que les effacements soient effectifs.

## Hébergement et données personnelles

L'hébergeur est sous-traitant des crèches : contrat de sous-traitance, registre des traitements, information des
familles. Hébergement recommandé en Nouvelle-Calédonie ou dans l'Union européenne.
