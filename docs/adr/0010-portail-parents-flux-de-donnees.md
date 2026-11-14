# ADR 0010 — Portail parents : flux de données

- **Statut** : acceptée, complétée par l'[ADR 0014](0014-medicaments-transmis-aux-parents.md) (médicament donné transmis aux parents)
- **Date** : 02/11/2026, mise à jour le 15/11/2026

## Contexte

Le portail parents est un service hébergé, commun à toutes les crèches, distinct de leurs instances
([ADR 0013](0013-web-heberge-instance-par-creche.md)). Les parents l'utilisent dans le navigateur ou avec
l'application Ti'Cagou Parents ([ADR 0023](0023-applications-mobiles-capacitor.md)).

Le portail contiendra des données personnelles d'enfants. Il doit donc en recevoir le moins possible, et rien qui
ne soit nécessaire aux parents. Les données de santé restent dans l'instance de la crèche.

## Décision

**Sens des connexions**
- L'instance de la crèche est la seule à ouvrir une connexion, en HTTPS vers le portail ; le portail n'appelle
  jamais une instance.
- **Envoi** : `POST /sync/v1/batch`. L'instance envoie sa file d'envoi (*outbox*, table `portal_outbox`)
  par lots d'au plus 200 éléments et 8 Mo.
  - Chaque élément porte un numéro de séquence croissant. Le portail ignore ce qu'il a déjà reçu, ce qui permet de
    renvoyer un lot sans risque après une coupure.
- **Remontée** : `GET /sync/v1/inbox?after=<curseur>`, puis `POST /sync/v1/inbox/ack`. L'instance *tire* les
  absences signalées, les messages des parents et les signatures.
  - Le portail efface ce qui a été acquitté.
  - Chaque élément remonté porte un identifiant ; l'instance ne l'applique qu'une fois.
- L'instance synchronise toutes les 60 s. Après un échec, il attend plus longtemps (backoff jusqu'à 30 min).
  Portail injoignable : la crèche fonctionne normalement, la file attend.

**Authentification de la structure**
- À l'activation, l'instance génère une paire **Ed25519**. Il garde la clé privée dans `server_secret`, comme
  les autres secrets de l'instance.
- Il envoie la clé publique au portail avec un **code d'activation à usage unique** remis par l'hébergeur
  (`POST /sync/v1/activate`). Le portail lui attribue alors un identifiant de structure.
- Chaque requête est signée sur `méthode \n chemin?requête \n date \n SHA-256 du corps`, avec trois en-têtes :
  `X-Ticagou-Structure`, `X-Ticagou-Date` et `X-Ticagou-Signature`.
- Le portail refuse une date écartée de plus de 5 min ou une signature fausse.
- Aucun mot de passe partagé ne circule. Une fuite de la base du portail ne permet pas de se faire passer pour la crèche.

**Ce qui sort : une liste blanche**
Le contenu envoyé n'est jamais l'entité métier. Ce sont des *projections* (`portal/PortalProjections`), construites
au moment de l'envoi à partir de l'état courant :

| Élément | Contenu envoyé |
|---|---|
| `GUARDIAN` | prénom, nom, e-mail, famille : seulement les responsables invités par la direction |
| `CHILD` | prénom, initiale du nom, section ; photo de profil si l'autorisation PHOTOS est accordée ; informations de santé **partagées explicitement** |
| `ATTENDANCE` | heures d'arrivée et de départ du jour |
| `ENTRY` | transmission : heure, type (repas, sieste, change, activité, photo, vidéo, mot), texte court, détails (quantité, durée) ; une vidéo de 30 s au plus part dans le champ `photo` ([ADR 0019](0019-videos-transmissions.md)) |
| `INVOICE` | facture ou avoir validé : numéro, mois, lignes (libellé et montant, comme sur le PDF), montants XPF, reste dû, PDF |
| `DOCUMENT` | document proposé à la signature (contrat, avenant, autorisation) : titre, PDF, empreinte, état |
| `MESSAGE` | message de l'équipe : prénom de l'auteur, texte ; un message « à plusieurs familles » part comme un message dans le fil de chacune |
| `NEWS` | actualité de la crèche : titre, texte, prénom de l'auteur, date de publication et de fin, pièce jointe facultative (PDF ou image, 5 Mo au plus), familles visées (toutes les familles invitées, ou celles qui ont un enfant inscrit dans la section) |
| `STRUCTURE` | nom, téléphone, coordonnées de virement, heure limite pour signaler une absence sans repas facturé (vide si la crèche ne le pratique pas : les parents n’en sont pas informés) |
| `BOOKING` | réponse à une demande d'accueil (lot 2) : jour, heures, ACCEPTEE ou REFUSEE, motif ; même identifiant que la demande du parent, qu'elle remplace |
| `PURGE_FAMILY`, `PURGE_STRUCTURE` | ordre d'effacement |

- **Santé** :
  - jamais d'allergie, de PAI, de régime, de vaccin, de médecin ni de consigne médicamenteuse par défaut ;
  - la DIRECTION peut partager une information précise (table `health_share`, tracée dans l'audit). Le texte est
    figé au moment du partage, et une modification ultérieure de la fiche ne sort pas toute seule.
- **Photos et vidéos** (ADR 0019) :
  - une photo ou une vidéo n'est envoyée que si l'éducatrice l'a marquée « à partager » ;
  - chaque enfant identifié sur la photo doit avoir l'autorisation PHOTOS accordée. Sinon, la photo ne sort pas.
- **Familles sans accès** : rien de ce qui la concerne ne sort tant qu'aucun responsable de la famille n'est invité.
- **Catégories** : la direction choisit ce qu'elle partage (transmissions, photos, factures, documents, messages et
  actualités, absences). Une catégorie décochée n'est plus envoyée.

**Ce qui remonte**
| Élément | Effet dans l'instance |
|---|---|
| `ABSENCE` | absence signalée, à confirmer par la direction ; elle devient alors une absence de facturation avec son motif |
| `MESSAGE` | message dans le fil de la famille |
| `BOOKING` | demande d'accueil occasionnel (jour, heures, mot) ou retard annoncé (heure) : la direction accepte ou refuse la demande, hors délai elle est refusée d'office ; un retard s'affiche sur la tablette |
| `SIGNATURE` | signature électronique simple : réponse (une autorisation peut être refusée, un contrat non), nom du compte du parent, date, IP, empreinte SHA-256 du PDF servi. Le tracé au doigt sert de geste de validation et n’est pas conservé. La direction enregistre la réponse, qui signe la version du contrat ou accorde (ou refuse) l’autorisation |

Le portail ne calcule rien. Une donnée remontée ne modifie jamais directement une facture, un contrat ou la santé :
la direction valide.

**Rattrapage** : une famille invitée en cours de route, ou un portail réactivé, reçoit les présences et les transmissions des 60
derniers jours, dans la limite de la conservation ci-dessous.

**Conservation et effacement**
- Sur le portail, les transmissions, présences et photos sont effacées après 60 jours, les factures et les documents
  après 24 mois, les actualités à leur date de fin ou après 12 mois. Une actualité retirée par la direction est effacée.
- Les fichiers y sont chiffrés au repos (AES-256-GCM).
- `PURGE_FAMILY` part à la suppression d'une famille. Le retrait de l'accès des responsables efface leur compte.
- **Désactivation** (Paramètres › Portail parents, ou licence sans le module `PORTAIL_PARENTS`) :
  - la file est vidée et un `PURGE_STRUCTURE` est envoyé, qui efface tout ce que le portail détient pour la structure ;
  - tant que le portail ne l'a pas acquitté, le serveur réessaie, et rien d'autre ne sort.

**Connexion des parents**
- Le parent saisit son e-mail. Il reçoit un lien valable 15 min, à usage unique, et un code à 6 chiffres
  (5 essais au plus).
- Le lien ou le code ouvrent une session (cookie HttpOnly, SameSite=Strict), révocable, qui suit l'abonnement de la
  crèche : elle court jusqu'à la fin d'abonnement plus la grâce (ou la fin d'évaluation), date envoyée par le serveur de
  la crèche (`accessUntil`). Un renouvellement l'allonge sans nouvel e-mail ; une crèche échue est traitée comme
  suspendue. Sans cette date (serveur de crèche plus ancien), 30 jours glissants.
- La réponse est identique, que l'adresse soit connue ou non.

## Conséquences

- La crèche reste maîtresse de ses données. Le portail n'est qu'une vitrine alimentée, effaçable d'un geste.
- Un parent voit ses données avec un retard d'une minute environ, et plus si la crèche n'a pas internet.
- Les fichiers sont envoyés encodés en Base64 dans le JSON : environ +33 %. C'est acceptable pour des photos
  réduites à 1600 px.
- Le contrat de synchronisation est décrit par des exemples JSON dans `parents-portal/contract/`, lus par les tests
  des deux côtés : un changement incompatible casse les tests.
- L'hébergeur du portail est un sous-traitant au sens du RGPD : contrat, registre des traitements et information des
  familles sont à prévoir.
- La signature électronique est simple : preuve d'intention et empreinte du document. Ce n'est pas une signature
  qualifiée.

## Écarts à la maquette « Ti'Cagou — app parents mobile »

- **Connexion** : lien magique et code à 6 chiffres au lieu du mot de passe de la maquette (choix validé) ; la case
  « Rester connecté » disparaît : la session dure autant que l'abonnement de la crèche.
- **Fil de la journée** : le régime (« Sans porc ») et les personnes autorisées ne sont pas affichés, car ce sont des
  données qui restent dans la crèche ; l'heure de départ prévue non plus. Les informations de santé que la direction
  partage apparaissent dans un encadré.
- **Factures** : pas de paiement en ligne ; les coordonnées de virement remplacent le choix du moyen de paiement.
  L'attestation annuelle n'est pas encore envoyée.
- **Documents** : la rubrique « De la crèche » (menus, projet pédagogique) reprend les pièces jointes des actualités ;
  le détail d'une sortie est dans le PDF de l'autorisation.
- **Actualités et album** (lot 1, sans maquette dédiée) : la dernière actualité des 14 derniers jours s'affiche en tête
  de la journée, avec un lien vers toutes les actualités ; un lien « Les photos de … » ouvre l'album de l'enfant (photos
  partagées des 60 derniers jours, téléchargeables). La barre du bas garde ses cinq rubriques.
- **Absence** : le bandeau « Prévenez avant 09:00 : le repas du jour ne vous sera pas facturé » ne s'affiche que si la
  direction a réglé cette heure dans Paramètres › Portail parents.
