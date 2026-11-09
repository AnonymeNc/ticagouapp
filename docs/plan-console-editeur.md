# Plan — Console de l'éditeur (tableau de bord administrateur)

- **Statut** : validé le 08/11/2026, avec les amendements du § 0 bis
- **Date** : 08/11/2026
- **Pour** : l'éditeur et hébergeur de Ti'Cagou (pas les crèches)

## 0. But

Un seul écran pour piloter le parc, sans passer par la ligne de commande ni lire les e-mails de supervision :

1. **Suivre toutes les crèches** : santé technique, abonnement, usage, alertes.
2. **Facturer les crèches** : abonnements, modules, prestations, avoirs, PDF, envoi par e-mail.
3. **Préparer automatiquement les relances** d'une facture impayée depuis *x* jours, selon des règles paramétrables ; vous
   les envoyez d'un clic.
4. **Produire des reportings** : chiffre d'affaires, encours, balance âgée, MRR, TGC, exports pour le comptable.

**Confirmé** : « les factures » sont celles de l'éditeur aux crèches (abonnement Ti'Cagou), pas celles des crèches aux
familles, qui existent déjà dans le module `FACTURATION`. Une relance par e-mail des familles est proposée en
§ 9, à part.

## 0 bis. Amendements du 08/11/2026

- **Un seul hébergeur** au départ, faute de moyens : pas de copie hors site. Tout ce qui en dépendait est retiré
  (copie hors site de la base de la console, état de la copie dans la vue du parc, idée d'alerte « copie hors site »).
  À reprendre quand un second hébergeur sera pris.
- **Aucun e-mail ne part sans clic.** Factures, relances et tout message à un client sont **préparés** dans une
  **boîte d'envoi** ; vous les relisez, les modifiez au besoin et cliquez sur *Envoyer* (un message ou une sélection).
  Seuls les e-mails de supervision existants, qui vous sont adressés, restent automatiques.
- **Maquette** : `docs/design/Ti'Cagou — Console éditeur.html`, à suivre pour toutes les phases.

**Avancement** : phase 1 livrée le 08/11/2026 (`console/`, [ADR 0017](adr/0017-console-editeur.md)) ; phase 2 livrée le
08/11/2026 (parc, fiche crèche, tableau de bord, recherche, heure des lectures en bas de la barre latérale) ; phase 3
livrée le 09/11/2026 (barème, TGC et mentions, contrats, factures et avoirs, paiements, boîte d'envoi, indicateurs) ;
phase 4 livrée le 09/11/2026 (relances préparées chaque jour ouvré, règles datées, règles par client, fériés, modèles).

Écarts encore assumés avec la maquette :
- fiche crèche : « Suspendre… » présent mais désactivé jusqu'aux ordres (phase 5) ; la ligne « Contrat : aligné » de
  la carte Santé est remplacée par l'état du portail parents ; l'alignement avec le contrat est dans l'onglet Abonnement ;
- relance 3 : la suspension est proposée sur le tableau de bord (« Décider »), elle s'exécutera par ordre en phase 5 ;
- l'e-mail d'une facture validée suit un texte fixe (modifiable message par message dans la boîte d'envoi) ;
- tableau de bord : pas encore de variation du revenu récurrent d'un mois sur l'autre (« + 13 500 F depuis
  octobre ») ; elle viendra avec le reporting (phase 6).

Choix de la phase 3 :
- factures et avoirs dans une même table (`type` FACTURE ou AVOIR), numérotés en deux séries (`F2026-0001`,
  `AV2026-0001`) par exercice civil ; l'avoir est validé dès son émission (code redemandé) ;
- la TGC est arrondie au franc par taux, sur le total hors taxe ; le taux appliqué est celui du jour d'émission ;
- l'immutabilité est garantie aussi en base (trigger) : seuls la date d'envoi et la suspension des relances changent
  encore sur une facture validée ;
- un avenant ferme la version du contrat en cours et en ouvre une autre au même barème ; si la période est déjà
  facturée, le complément est préparé en brouillon et ce qui est à rendre est indiqué pour un avoir ;
- le délai de préparation des renouvellements (45 jours, 10 pour le mensuel) est un réglage de l'application ;
- les remises s'additionnent, calculées chacune sur le sous-total de l'abonnement.

Choix de la phase 4 :
- une relance groupée prend le ton du niveau le plus élevé de ses factures ; chaque facture garde son niveau ;
- après un arrêt, on rattrape au niveau le plus élevé atteint, sans envoyer les niveaux intermédiaires ;
- le décalage propre à un client ne touche pas le rappel courtois (avant échéance) ;
- une relance écartée à la main n'est pas préparée à nouveau ; une relance retirée parce qu'une facture a été soldée
  l'est pour les factures encore dues.

Choix de la phase 2 :
- le statut commercial n'a que trois valeurs saisies (prospect, client, résilié) ; évaluation, grâce, lecture seule et
  suspension se lisent dans l'état reçu (licence, conteneur), pour ne jamais diverger de l'instance ;
- un client est créé avec chaque crèche (raison sociale modifiable) ; regrouper plusieurs crèches sous un même client
  (collectivité) viendra avec la facturation si le besoin se confirme.

## 1. Ce qui existe déjà et sur quoi on s'appuie

| Brique | Où | Ce qu'on en garde |
|---|---|---|
| Supervision : état toutes les 15 min, alertes, e-mail, récapitulatif 7 h | `parents-portal/.../supervision`, ADR 0012 | Source des états techniques ; la console la lit au lieu de la refaire |
| API d'administration (jeton `PORTAL_ADMIN_TOKEN`) | `parents-portal/.../admin/AdminController` | Lue par la console sur le réseau Docker interne ; Caddy bloque déjà `/admin/*` depuis internet |
| Abonnement d'une instance (table `subscription`) | `creche.sh abonnement`, ADR 0013 / 0015 | Reste la vérité côté instance ; la console finira par l'écrire (§ 7) |
| État du parc | `creche.sh etat` | Étendu en `etat --json` pour alimenter la console (§ 4) |
| Arrondis XPF, TGC paramétrable, avoirs, numérotation, relances PDF | `server/.../billing` (`Xpf`, `TgcRate`, `InvoiceCalculator`, `ReminderService`) | Mêmes règles et mêmes tests, recopiés dans la console (pas de dépendance entre applications) |
| PDF Thymeleaf + openhtmltopdf | ADR 0004 | Même moteur pour les factures de l'éditeur |
| TOTP sans dépendance | `server/.../auth/Totp` | Double authentification de la console |
| Barème, formules, modules | `docs/commercial/bareme-tarifaire.md`, ADR 0015 | Devient un catalogue en base, versionné par dates de validité |

## 2. Décisions à prendre (ADR 0017 — Console de l'éditeur)

| # | Question | Recommandation | Alternative |
|---|---|---|---|
| D1 | Où vit la console ? | **Nouvelle application `console/`** (Spring Boot + React, même pile), sa propre base `console`, son conteneur dans le socle (profil `console`) | L'ajouter au portail parents : moins de code, mais le portail est ouvert aux parents sur internet et la console détient les données commerciales et, plus tard, le pouvoir d'agir sur les instances |
| D2 | Qui y accède, comment ? | **Vous seul** (comptes nominatifs pour un futur associé), mot de passe + **TOTP obligatoire**, session courte, **accès restreint par le réseau** : WireGuard sur le serveur, ou liste d'adresses IP dans Caddy | Internet ouvert avec TOTP seul : déconseillé |
| D3 | Comment la console agit sur les instances ? | **Jamais d'accès au socket Docker.** La console dépose des *ordres* ; une tâche `cron` de l'hôte (`creche.sh appliquer`) les tire, les exécute (liste blanche : `abonnement`, `suspendre`, `reprendre`, `mettre-a-jour`) et rend compte | Socket Docker monté dans la console : une faille web = prise de contrôle de toutes les crèches |
| D4 | La supervision reste-t-elle dans le portail ? | **Oui pour l'instant** ; la console la lit. La déplacer dans la console plus tard est possible (ADR à part) | La déplacer tout de suite : plus propre, mais retouche les instances et l'ADR 0012 |
| D5 | Les factures de renouvellement | **Brouillons générés automatiquement** 45 jours avant l'échéance, **validés par vous** (un clic, ou en lot) | Validation automatique : à activer plus tard, par réglage |
| D6 | Paiement et abonnement | Une facture d'abonnement **soldée** prolonge l'abonnement de l'instance (ordre `abonnement`), après confirmation | Prolongation manuelle, comme aujourd'hui |
| D7 | Règle « `structure_id` partout » | Ne s'applique pas : la console n'a qu'un locataire (l'éditeur). Les tables portent `client_id` / `creche_id` | — |
| D8 | Maquettes | `docs/design/` n'a **aucune maquette** de console : en produire une (Claude Design) avant la phase 2, ou accepter de suivre le système visuel existant (shadcn, Fraunces / Source Sans) | — |

## 3. Phase 1 — Socle de la console (3 à 4 jours)

**Dépôt**
- `console/server` (Java 21, Spring Boot 3, Flyway, Spring Security) et `console/web` (React 19, Vite, Tailwind, shadcn,
  TanStack Query/Table, React Hook Form + Zod), sur le modèle de `parents-portal/`.
- API `/api/v1`, erreurs RFC 7807, client typé généré depuis l'OpenAPI, fuseau `Pacific/Noumea`, dates JJ/MM/AAAA.

**Sécurité**
- Table `operator` (identifiant, haché BCrypt, secret TOTP chiffré, dernier pas TOTP), connexion + TOTP obligatoire
  dès le premier compte (lien `/premier-compte?jeton=<CONSOLE_SETUP_TOKEN>`, sans effet dès qu'un compte existe).
- Cookie de session `HttpOnly`, `Secure`, `SameSite=Strict`, 8 h, expiration à 30 min d'inactivité.
- Limitation des connexions manquées (adresse et compte).
- Ré-authentification TOTP pour les actions sensibles : valider une facture, émettre un avoir, envoyer un ordre à une instance.
- **Journal d'audit** (`audit_event`) de toute écriture : qui, quoi, quand, avant/après.

**Déploiement**
- Service `console` dans `deploy/socle.yml` (profil `console`), base `console` et rôle dédiés créés par `creche.sh socle --console`.
- Site Caddy `console.<domaine>` avec `remote_ip` restreint (D2) ; `/actuator` bloqué.
- Variables : `CONSOLE_DB_*`, `CONSOLE_PORTAL_ADMIN_TOKEN`, `CONSOLE_HOST_TOKEN`, `CONSOLE_SMTP_*`, `CONSOLE_MAIL_FROM`
  (`facturation@…`, SPF/DKIM à régler sur le domaine).
- Sauvegarde chiffrée de la base `console` la nuit, sur le même serveur que les crèches (pas de second hébergeur pour
  l'instant : § 0 bis). Conservation des factures :
  10 ans.

**Tests** : Testcontainers (connexion, TOTP, refus sans TOTP, audit), Vitest (écran de connexion).

## 4. Phase 2 — Parc des crèches (4 à 5 jours)

**Données**
- `client` : raison sociale, RIDET, adresse, contacts (direction, facturation, e-mail de relance), conditions de paiement
  (30 jours par défaut ; collectivité : 45 jours, mandat administratif), notes.
- `creche` : `slug` (clé commune avec `creche.sh`), client, domaine, date de création, statut commercial
  (`PROSPECT`, `EVALUATION`, `ACTIF`, `GRACE`, `LECTURE_SEULE`, `SUSPENDU`, `RESILIE`).
- `creche_snapshot` : état reçu (supervision + hôte) toutes les 15 min, réduit à un point par jour après 30 jours
  (courbes disque, usage).
- `alert_history` : copie des alertes ouvertes et fermées (le portail ne garde que l'état courant).

**Sources**
1. **Supervision** : la console appelle `GET http://portail:8090/admin/v1/supervision` toutes les 5 min.
   - Côté portail : enregistrer le `slug` sur `supervision_site` (repris du libellé du code, que `creche.sh creer` remplit
     déjà avec le slug) et ajouter `GET /admin/v1/supervision/alerts?since=` pour l'historique.
2. **Hôte** : `creche.sh etat --json` (nouveau), lancé par `cron` toutes les 5 min, poste vers
   `http://console:8095/interne/v1/hote` (jeton `CONSOLE_HOST_TOKEN`, réseau interne) :
   état du conteneur, image, abonnement lu dans chaque base (`valid_until`, offre, modules, places, PC, tablettes),
   disque de l'hôte.
3. **Usage** (nouveau, côté instance) : ajouter à `MonitoringStatus` un bloc `usage` **sans donnée personnelle**, rien que
   des nombres : enfants actifs, places occupées, utilisateurs actifs sur 30 jours, dernière connexion d'un compte
   direction (date seulement), pointages des 7 derniers jours, factures émises le mois dernier, PC et tablettes utilisés.
   Ajouter `usage` à la liste blanche `SupervisionService.FIELDS` et étendre le test « aucune donnée personnelle ».
   Amender l'ADR 0012.

**Écrans**
- **Vue du parc** : une ligne par crèche, triable et filtrable — pastille de santé (vert / orange / rouge), dernier
  contact, version, formule et modules, échéance de l'abonnement et statut, places, PC et tablettes utilisés / permis,
  sauvegarde, disque, alertes ouvertes, solde dû.
- **Bandeau du haut** : crèches en alerte, évaluations qui finissent dans 7 jours, abonnements à renouveler sous 30 jours,
  factures en retard, versions en retard.
- **Fiche crèche** : onglets Santé (courbes, historique d'alertes), Abonnement, Usage, Factures, Contacts, Notes, Journal.
- **Notifications** : les e-mails de supervision restent ; la console n'en ajoute pas, tout se lit sur le tableau de bord.

**Tests** : correspondance supervision ↔ hôte par slug, réduction des instantanés, calcul de la pastille, test
« aucune donnée personnelle » étendu à `usage`.

## 5. Phase 3 — Facturation de l'éditeur (6 à 8 jours)

**Règles reprises du logiciel** : montants XPF en `long`, aucune décimale ; facture validée immuable ; correction par
avoir ; numérotation continue par exercice (`F2026-0001`, `AV2026-0001`) ; TGC par taux et par date de validité ;
arrondi au franc par taux, sur le total hors taxe.

**Catalogue (tables paramétrables avec dates de validité, rien en dur)**
- `price_list` (version du barème, `valid_from`, `valid_to`).
- `price_item` : formule × tranche de places (Micro / Structure / Grande), modules à la carte, PC et tablette
  supplémentaires, support Premium, prestations ponctuelles (mise en route, formations, saisie papier, intervention).
- `discount_rule` : engagement 3 ans (− 10 %), structure supplémentaire du même client (− 15 %), remise libre motivée.
- `tgc_rate` : taux par nature de prestation, avec dates (11 % à confirmer par le comptable).

**Contrat d'abonnement** (`subscription_contract`)
- Client, crèche, formule, tranche, modules ajoutés, PC et tablettes en plus, support, engagement (1 ou 3 ans), rythme
  (annuel d'avance ou mensuel), date de début, date de fin, tarif figé à la signature.
- Changement en cours de période (module ajouté, tranche de places) : **prorata au jour**, facture complémentaire ou avoir.
- Le contrat calcule l'abonnement attendu de l'instance (offre, modules, places, PC, tablettes, `valid_until`).

**Factures** (`invoice`, `invoice_line`, `credit_note`, `payment`, `payment_allocation`)
- Création manuelle (prestations), ou **génération des brouillons de renouvellement** 45 jours avant l'échéance (D5).
- Cycle : brouillon → validée (numéro attribué, PDF figé et stocké avec son empreinte SHA-256) → envoyée → partiellement
  payée → payée ; ou annulée par avoir.
- PDF : gabarit Thymeleaf avec mentions de l'éditeur (raison sociale, RIDET, numéro TGC, coordonnées bancaires),
  détail par ligne, TGC par taux, échéance, pénalités de retard. **Mentions à valider par le comptable.**
- À la validation, l'e-mail au contact facturation (PDF joint, `MimeMessage`) est **préparé dans la boîte d'envoi** ; il
  part quand vous cliquez sur *Envoyer*. Journal d'envoi.
- Paiements : saisie manuelle (virement, chèque, espèces), affectation à une ou plusieurs factures, trop-perçu.
- Plus tard : import d'un relevé bancaire CSV et rapprochement proposé par montant et référence.

**Tests unitaires obligatoires** : prix d'une formule selon la tranche, modules, remises cumulées, prorata, TGC et
arrondis, numérotation sans trou sous concurrence, immutabilité (refus de modifier une facture validée), avoir partiel.

## 6. Phase 4 — Relances préparées automatiquement (2 à 3 jours)

**Règles paramétrables** (`reminder_rule`, avec dates de validité)

| Niveau | Délai par défaut après l'échéance | Ton | Action |
|---|---|---|---|
| 0 | 3 jours **avant** l'échéance | Rappel courtois | E-mail facultatif |
| 1 | 7 jours | Rappel de paiement | E-mail + PDF |
| 2 | 15 jours | Deuxième relance | E-mail + PDF, copie à l'éditeur |
| 3 | 30 jours | Dernière relance, avant suspension | E-mail + PDF ; l'éditeur reçoit une proposition de suspension |

- Délais, objets et textes modifiables (modèles avec variables : client, numéro, montant restant, échéance, jours de retard).
- **Par client** : délais propres (collectivités), relances suspendues (litige, échéancier accordé), contact dédié.
- **Préparation** : tâche quotidienne à 6 h (Nouméa), jours ouvrés seulement ; elle **prépare** les relances dues dans la
  boîte d'envoi et n'envoie rien. Idempotente (une relance par niveau et par facture, contrainte d'unicité) ; rattrapage
  si le serveur était arrêté ; un seul message groupé par client si plusieurs factures sont en retard.
- **Envoi sur clic** : la boîte d'envoi montre chaque message (destinataire, objet, texte, pièces jointes), modifiable ;
  *Envoyer* pour un message, *Envoyer la sélection* pour plusieurs, *Écarter* pour l'abandonner (motif noté).
- Une facture soldée entre-temps n'est jamais relancée : vérification au clic, le message est alors retiré.
- Journal : date, niveau, destinataire, résultat SMTP ; visible sur la facture et la fiche crèche.
- **Jamais de suspension automatique** : la console propose, vous confirmez (ordre `suspendre`, § 7).
  Rappel ADR 0011 : la lecture seule bloque le pointage ; une suspension doit rester une décision humaine.

**Tests** : calcul des dates (jours ouvrés, fériés de Nouvelle-Calédonie paramétrables), idempotence, facture payée la
veille, client en pause, regroupement par client.

## 7. Phase 5 — Ordres aux instances (3 à 4 jours)

- Table `instance_order` : type (`ABONNEMENT`, `SUSPENDRE`, `REPRENDRE`, `METTRE_A_JOUR`), crèche, paramètres, demandé par,
  état (`EN_ATTENTE`, `EN_COURS`, `FAIT`, `ECHEC`), sortie.
- `creche.sh appliquer` (cron, chaque minute) : `GET /interne/v1/ordres`, exécution par la commande existante, compte rendu.
  Liste blanche stricte côté script, paramètres validés (mêmes contrôles que `cmd_abonnement`).
- Déclencheurs dans la console :
  - facture d'abonnement soldée → ordre `ABONNEMENT` avec la nouvelle échéance (D6), confirmé par vous ;
  - contrat modifié (module, places) → ordre `ABONNEMENT` ;
  - mise à jour : une crèche témoin, puis le reste, depuis la vue du parc.
- Écart détecté entre le contrat et l'abonnement lu dans l'instance → alerte « abonnement non aligné ».

**Tests** : script `appliquer` contre une base de test, refus d'un ordre hors liste, ré-exécution sans doublon.

## 8. Phase 6 — Reporting (3 à 4 jours)

| Rapport | Contenu |
|---|---|
| Tableau de bord | MRR et ARR, nombre de crèches par statut, encours, retard moyen de paiement (DSO), évaluations en cours |
| Chiffre d'affaires | Facturé et encaissé par mois, par formule, par module, par type (abonnement / prestation) |
| Balance âgée | Restant dû par client : non échu, 0–30, 31–60, 61–90, plus de 90 jours |
| Parc | Répartition formules, modules, tranches ; PC et tablettes au-delà du contrat (vente additionnelle) |
| Risque de départ | Crèches sans connexion direction depuis 14 jours, usage en baisse, retards de paiement répétés |
| TGC | TGC collectée par taux et par période, pour la déclaration |
| Rentabilité | Revenu par crèche face au coût d'hébergement réparti et au temps de support saisi (§ 9) |

- Exports **CSV et Excel** (même bibliothèque que l'ADR 0006) ; **journal des ventes et des encaissements** au format attendu
  par le comptable (à demander), éventuellement FEC.
- Filtres : période, client, formule ; dates JJ/MM/AAAA.

**Tests** : MRR (abonnement annuel ramené au mois, prorata), balance âgée aux bornes, totaux TGC égaux aux factures.

## 9. Autres idées (à trier)

**Fort intérêt, peu de travail**
- **Fin d'évaluation et renouvellement** : liste « à appeler cette semaine » (évaluation finissant dans 7 jours, abonnement
  sous 30 jours sans facture émise).
- **Temps de support par crèche** : saisie rapide (date, durée, motif). Le barème suppose 0,75 h par client et par mois :
  vous le mesurez enfin, et la rentabilité d'Essentiel Micro devient visible.
- **Notes et historique client** : appels, démos, engagements pris.

**Intérêt moyen**
- **Pipeline commercial** : prospect → démonstration → évaluation → client, avec les crèches de démonstration.
- **Message aux directrices** : bandeau d'information (maintenance prévue, nouveauté) transmis par la réponse de la
  supervision. Change le sens de l'ADR 0012 (« rien n'est renvoyé ») : ADR à part.
- **Page de statut publique** (`statut.<domaine>`) : disponibilité des crèches sans les nommer, maintenances annoncées.
- **Factures de l'éditeur visibles par la directrice** dans Paramètres › Abonnement (via le même canal).
- **Suivi des versions** : crèches en retard de version, notes de version.

**Côté crèches (module `FACTURATION`, hors console)**
- **Relance des familles par e-mail** : aujourd'hui les relances sont des lettres PDF notées à la main
  (`billing/ReminderService`). Ajouter l'envoi par e-mail, avec les mêmes règles paramétrables que § 6 — c'est très
  probablement la même demande vue du côté de vos clientes, et un argument de vente.

**Plus tard**
- Paiement en ligne (prestataire local à identifier) ; prélèvement mensuel (à vérifier avec la banque, le prélèvement SEPA
  ne s'applique pas au franc CFP) ; déplacement de la supervision dans la console (D4).

## 10. Ordre, charge et jalons

| Phase | Contenu | Charge | Jalon |
|---|---|---|---|
| 0 | ADR 0017, réponses aux questions du § 11, maquette | 1 à 2 j | Décisions écrites |
| 1 | Socle, sécurité, déploiement | 3 à 4 j | Connexion TOTP sur `console.<domaine>` |
| 2 | Parc des crèches | 4 à 5 j | `creche.sh etat` et les e-mails ne sont plus nécessaires au quotidien |
| 3 | Facturation | 6 à 8 j | Première facture réelle validée et envoyée |
| 4 | Relances | 2 à 3 j | Premières relances préparées, puis envoyées d'un clic |
| 5 | Ordres aux instances | 3 à 4 j | Paiement → abonnement prolongé sans ligne de commande |
| 6 | Reporting | 3 à 4 j | Export remis au comptable |
| | **Total** | **22 à 30 j** | |

Les phases 3 et 4 peuvent passer avant la 2 si la facturation presse : elles ne dépendent que du socle et du référentiel
client / crèche (saisi à la main en attendant la phase 2).

Chaque phase : une branche, une PR, `./mvnw verify` et `pnpm test` verts, `docs/EXPLOITATION.md` et le wiki mis à jour.

## 11. Questions ouvertes

1. ~~Les factures visées sont-elles bien celles de l'éditeur aux crèches ?~~ Oui.
2. Accès : WireGuard (plus sûr) ou liste d'adresses IP dans Caddy (plus simple) ?
3. Forme juridique, RIDET, assujettissement et taux de TGC, mentions obligatoires : à faire valider par le comptable.
4. Validation des factures de renouvellement : manuelle (recommandé au début) ou automatique ?
5. Délais de relance par défaut : ceux du § 6 conviennent-ils ?
6. Prolonger l'abonnement d'une instance dès le paiement, après votre confirmation : d'accord ?
7. Adresse d'envoi des factures et relances (`facturation@…`) et fournisseur SMTP.
8. ~~Maquette de la console~~ : faite (§ 0 bis).
