# ADR 0017 — Console de l'éditeur

- **Statut** : acceptée
- **Date** : 08/11/2026
- **Plan** : [plan-console-editeur.md](../plan-console-editeur.md) · **Maquette** : `docs/design/Ti'Cagou — Console éditeur.html`

## Contexte

L'éditeur suit ses crèches par `creche.sh etat` et les e-mails de supervision (ADR 0012), et facture à la main. Il lui
faut un tableau de bord du parc, la facturation de ses abonnements, la préparation des relances et des reportings.

## Décision

**Une application à part, `console/`** (Spring Boot 3 + React 19, même pile que le reste), avec sa base `console` et son
rôle PostgreSQL, dans le socle (profil `console`). Elle n'est pas ajoutée au portail parents : celui-ci est ouvert aux
parents sur internet, la console détient les données commerciales et, plus tard, le droit de donner des ordres aux
instances.

**Accès**
- Comptes nominatifs (`operator`), mot de passe (BCrypt) **et TOTP obligatoire** (même algorithme que `auth/Totp`, sans
  dépendance). Le secret TOTP est chiffré en base (AES-256-GCM, `CONSOLE_SECRET_KEY`).
- Premier compte par un jeton à usage unique (`CONSOLE_SETUP_TOKEN`, écrit par `creche.sh socle --console`), comme
  l'assistant d'une crèche (ADR 0013). Téléphone perdu : `creche.sh console-telephone-perdu <identifiant>`.
- Session côté serveur, cookie `HttpOnly`, `Secure`, `SameSite=Strict` ; 30 minutes d'inactivité, 8 heures au plus.
  Jeton CSRF en en-tête pour toute écriture.
- Connexions manquées limitées : 20 par adresse en 10 minutes, compte bloqué 15 minutes après 5 échecs.
- **Code TOTP redemandé** pour les actions sensibles (valider une facture, émettre un avoir, confirmer un ordre) : valable
  5 minutes.
- **Réseau** : Caddy n'accepte que les adresses de `CONSOLE_ADRESSES_AUTORISEES` ; sans liste, la console n'est pas
  publiée (accès par tunnel SSH). `/actuator` reste bloqué.
- **Journal** (`audit_event`) de toute connexion et de toute écriture.

**Aucun e-mail ne part sans clic** de l'éditeur : factures et relances sont préparées dans une boîte d'envoi.

**La console ne touche jamais Docker** : elle dépose des ordres que `creche.sh appliquer` (cron de l'hôte) exécute, sur
une liste blanche (phase 5).

**Supervision** : reste dans le portail ; la console la lit par l'API d'administration, sur le réseau Docker interne,
toutes les 5 minutes (`PORTAL_ADMIN_TOKEN`). L'hôte, lui, envoie son rapport (`creche.sh rapport-console`, cron toutes les
5 minutes : conteneur, image, abonnement de chaque crèche, disque) à `POST /interne/v1/hote` avec `CONSOLE_HOST_TOKEN`,
sur `127.0.0.1:8095`. Caddy ne publie jamais `/interne`. Les deux sources se rejoignent par le slug de la crèche.

**Un seul hébergeur** pour l'instant : la base de la console est sauvegardée sur le même serveur, sans copie hors site.

La règle « `structure_id` sur toute table métier » ne s'applique pas : la console n'a qu'un locataire, l'éditeur.

## Alternatives écartées

- **Dans le portail parents** : moins de code, mais même surface d'attaque que l'application des parents.
- **Socket Docker monté dans la console** : une faille web donnerait la main sur toutes les crèches.
- **Envoi automatique des relances** : refusé par l'éditeur, qui veut relire chaque message.

## Conséquences

- Un conteneur de plus (environ 300 Mo) et une base à sauvegarder.
- Les règles de facturation (XPF entiers, immutabilité, avoirs, numérotation) sont recopiées depuis `server/billing`, avec
  leurs tests, plutôt que partagées : les deux applications évoluent séparément.
- Tant qu'il n'y a qu'un hébergeur, perdre le serveur fait perdre les factures de l'éditeur : exporter le journal des
  ventes chaque mois et le remettre au comptable.
