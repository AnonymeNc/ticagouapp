# Fiches des applications dans les stores

[← Wiki](../../wiki/README.md) · Décisions du [plan](../../plan-application-mobile.md) : stores **publics** pour les trois
applications (décision 2), comptes développeur au nom de la **société éditrice** (décision 3, numéro D-U-N-S requis).
Captures : `docs/commercial/captures/stores/` (formats exigés, données de démonstration). Confidentialité :
[declarations-confidentialite.md](declarations-confidentialite.md) et
[politique-confidentialite.md](politique-confidentialite.md).

Langue principale : **français (France)**. Pays : Nouvelle-Calédonie, France (les familles métropolitaines de passage),
autres territoires d'outre-mer selon la clientèle.

---

## Ti'Cagou Direction

| Champ | Texte |
|---|---|
| Nom (30) | Ti'Cagou Direction |
| Sous-titre iOS (30) | Gérer sa crèche partout |
| Description courte Google (80) | La crèche dans votre poche : encadrement en direct, présences, départs à valider. |
| Catégorie | Business (iOS) · Professionnel (Google) |
| Âge | 4+ / Tout public |
| Prix | Gratuit (l'abonnement de la crèche est vendu hors de l'application ; pas d'achat intégré) |
| Mots-clés iOS (100) | crèche,micro-crèche,garderie,pointage,encadrement,facturation,petite enfance,direction |

**Texte promotionnel (170)** : Encadrement en direct, départs bloqués validés à distance, alertes sur votre téléphone :
toute votre crèche Ti'Cagou dans la poche.

**Description**

> Ti'Cagou Direction est l'application des équipes de direction des crèches et garderies qui utilisent Ti'Cagou.
> C'est le même logiciel que sur l'ordinateur du bureau, adapté au téléphone et à la tablette.
>
> AU QUOTIDIEN
> • Le taux d'encadrement de chaque section, en direct.
> • Les présences du jour, les arrivées attendues et les absences signalées par les familles.
> • Les fiches des enfants, avec allergies, PAI et régime toujours visibles.
> • Un départ bloqué à l'accueil (personne absente de la liste) ? Vous êtes prévenue et vous autorisez ou refusez
>   depuis votre téléphone, avec un motif tracé.
> • Messages des familles, repas du jour, personnel présent.
>
> LA GESTION
> • Facturation du mois : contrôle et validation.
> • Inscriptions, contrats, statistiques, paramètres.
>
> SÉCURITÉ
> • Double authentification, Face ID ou empreinte à chaque ouverture.
> • Aucune information de santé dans les notifications.
> • Un téléphone perdu se retire depuis l'ordinateur de la crèche.
>
> Ti'Cagou Direction nécessite un abonnement Ti'Cagou de votre crèche (code de la crèche ou QR code fourni à la mise en
> service). Logiciel conçu en Nouvelle-Calédonie, pour les structures d'accueil de la petite enfance.

## Ti'Cagou Pointage (Google Play seulement, décision 5)

| Champ | Texte |
|---|---|
| Nom (30) | Ti'Cagou Pointage |
| Description courte (80) | La tablette d'accueil de la crèche : arrivées, départs, même sans internet. |
| Catégorie | Professionnel |
| Âge | Tout public |

**Description**

> Ti'Cagou Pointage transforme une tablette Android en borne d'accueil pour votre crèche Ti'Cagou.
>
> • Arrivées et départs des enfants et du personnel, d'un geste.
> • Départ bloqué si la personne n'est pas autorisée ; validation par la direction sur place ou à distance.
> • Allergies et PAI visibles sur chaque carte.
> • Fonctionne sans internet : les pointages attendent, chiffrés, le retour du réseau.
> • Mode kiosque : la tablette reste sur Ti'Cagou.
> • Photos et vidéos de la journée partagées avec les familles (selon les autorisations).
>
> Appairage par le QR code affiché sur l'ordinateur de la crèche. Nécessite un abonnement Ti'Cagou.

## Ti'Cagou Parents

| Champ | Texte |
|---|---|
| Nom (30) | Ti'Cagou Parents |
| Sous-titre iOS (30) | La journée de votre enfant |
| Description courte Google (80) | Repas, sieste, photos, messages : la journée de votre enfant à la crèche. |
| Catégorie | Éducation (iOS et Google) |
| Âge | 4+ / Tout public |
| Mots-clés iOS (100) | crèche,bébé,parents,journée,photos,garderie,micro-crèche,messages,factures |

**Description**

> Ti'Cagou Parents relie votre famille à la crèche de votre enfant, si elle utilise Ti'Cagou.
>
> • La journée de votre enfant : repas, sieste, changes, activités.
> • Les photos et vidéos partagées par l'équipe.
> • Les messages avec la crèche, les actualités.
> • Signaler une absence, un retard, demander un accueil en plus.
> • Vos factures et documents à signer.
> • Des notifications quand la crèche partage quelque chose (vous choisissez lesquelles).
>
> Connexion avec l'adresse e-mail donnée à la crèche : vous recevez un code. Aucune information de santé n'apparaît dans
> les notifications. Vous pouvez supprimer votre compte depuis l'application.

---

## Relecture par Apple et Google : compte de démonstration

Les relecteurs doivent pouvoir se connecter (règle 2.1 d'Apple). Avant chaque soumission :

1. Une crèche de démonstration hébergée : `./creche.sh demo relecture` (données fictives), avec son code d'annuaire.
2. Direction : identifiant `relecture.direction`, mot de passe fort tiré pour la relecture, **double authentification
   désactivée pour ce compte seulement** (sinon : donner un code TOTP valable dans les notes, impossible en pratique).
3. Parents : adresse `relecture.parents@ticagou.nc` invitée dans la crèche de démonstration ; boîte lisible par
   l'éditeur, code donné dans les notes au moment de la relecture, ou code fixe de relecture configuré sur le portail.
4. Pointage : la tablette se relie par le code d'appairage à 6 chiffres affiché dans Paramètres de la démonstration.

**Notes pour la relecture (modèle)**

> Ti'Cagou is a management software for childcare centres (crèches) in New Caledonia. The app requires a subscription
> purchased outside the app by the childcare centre; there is no in-app purchase and no content for sale.
> Demo: tap « Taper le code de la crèche », enter <CODE>, then sign in with <identifiant> / <mot de passe>.
> Notifications never contain health data. Account deletion (Parents): Mon compte › Supprimer mon compte.

## Achats intégrés

Aucun. L'abonnement est vendu par l'éditeur aux crèches (contrat, facture) ; les factures des crèches aux familles
paient un service physique (l'accueil des enfants), hors du champ des achats intégrés (règle 3.1.3(e) d'Apple, services
réels hors de l'application).
