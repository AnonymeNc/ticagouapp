# ADR 0017 — Console de l'éditeur

- **Statut** : acceptée, complétée par l'[ADR 0025](0025-choix-de-la-creche.md) (annuaire des codes de crèche)
- **Date** : 08/11/2026
- **Maquette** : `docs/design/Ti'Cagou — Console éditeur.html`

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
- **Réseau** (`CONSOLE_ACCES`, amendement du 11/11/2026) : `internet` par défaut, la console est joignable depuis
  n'importe quel ordinateur ; `adresses` la réserve aux adresses IP de `CONSOLE_ADRESSES_AUTORISEES` ; `tunnel` ne publie
  aucun site. La liste d'adresses liait la console à un réseau (l'adresse IP de la box du bureau), pas à un ordinateur :
  elle empêchait de travailler en déplacement ou derrière une adresse qui change, sans protéger davantage le compte, que
  gardent déjà le mot de passe, le code du téléphone (obligatoire, redemandé pour les actions sensibles), les limites de
  tentatives et le journal. Un `socle.env` sans `CONSOLE_ACCES` garde l'ancien comportement : rien ne s'ouvre seul ; un
  réglage invalide arrête `creche.sh` avant d'écrire le site. `/actuator` et `/interne` restent bloqués.
- **Journal** (`audit_event`) de toute connexion et de toute écriture.

**Aucun e-mail ne part sans clic** de l'éditeur : factures et relances sont préparées dans une boîte d'envoi. L'envoi
passe par le SMTP de la console (`CONSOLE_SMTP_*`, expéditeur `CONSOLE_MAIL_FROM`) ; un refus du serveur de messagerie
laisse le message en attente avec l'erreur.

**Facturation** (phase 3) : montants en francs entiers, barème et TGC en tables datées, facture validée immuable (en base
aussi) et corrigée par avoir, numérotation continue par série et par exercice, PDF produit une fois à la validation et
conservé avec son empreinte SHA-256, mentions de l'éditeur recopiées dans la facture. Valider une facture et émettre un
avoir redemandent le code.

**La console ne touche jamais Docker** : elle dépose des ordres que `creche.sh appliquer` (cron de l'hôte, chaque minute)
exécute, sur une liste blanche revérifiée par le script : créer une crèche, lancer ou supprimer une démonstration,
abonnement, suspendre, reprendre, mettre à jour. Chaque ordre est pris une seule fois et rend compte (sortie, lien de
démarrage). Les ordres donnés demandent le code ; ceux que la console propose attendent une confirmation.

**Supprimer une crèche cliente** (amendement du 09/11/2026) passe aussi par la console, en deux ordres : EXPORTER (base,
documents et clés dans une archive chiffrée avec `CONSOLE_SECRET_KEY`, que la console lit sur un dossier monté en
lecture seule) puis SUPPRIMER. La console ne crée le second qu'après avoir servi l'export en entier et que le navigateur
de l'éditeur l'a reçu ; il nomme l'export, que `creche.sh` revérifie (présent, de cette crèche, de moins de 24 h) avant
d'effacer. Deux mises en garde précèdent : ce qui est effacé, puis l'identifiant retapé et le code. Ainsi rien n'est
effacé sans une copie complète sur l'ordinateur de l'éditeur, et `creche.sh reimporter` la remet en service.

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
