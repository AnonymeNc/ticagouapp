# Contrat de synchronisation instance de crèche ↔ portail parents

Exemples de référence du protocole décrit dans `docs/adr/0010-portail-parents-flux-de-donnees.md`. Les tests du
serveur (`server/`) et du portail (`parents-portal/server/`) lisent ces fichiers : un changement de format
qui n'est pas répercuté des deux côtés casse les tests.

| Fichier | Requête |
|---|---|
| `activate.json` | `POST /sync/v1/activate` (non signée, code à usage unique) → `{ "structureId": "…" }` |
| `batch.json` | `POST /sync/v1/batch` (signée) → `batch-response.json` |
| `inbox.json` | réponse de `GET /sync/v1/inbox?after=0` (signée) ; acquittement `POST /sync/v1/inbox/ack {"upTo": 5}` |

Signature : Ed25519 de `MÉTHODE\nchemin?requête\ndate ISO-8601\nSHA-256 hexadécimal du corps`, en-têtes
`X-Ticagou-Structure`, `X-Ticagou-Date`, `X-Ticagou-Signature` (Base64). Dates `Instant` en UTC, jours au format
ISO, montants XPF entiers.
