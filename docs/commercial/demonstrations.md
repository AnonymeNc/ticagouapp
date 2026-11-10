# Démonstration de A à Z

[← Wiki](../wiki/README.md)

Une seule formule, tout compris ([ADR 0021](../adr/0021-formule-unique.md)) : la démonstration montre donc tout Ti'Cagou,
sur une crèche qui tourne depuis 18 mois. Comptez **45 minutes** pour le parcours complet, 20 minutes pour la version
courte (étapes marquées ★).

## Préparer

```powershell
powershell -ExecutionPolicy Bypass -File scripts\tester-en-local.ps1 -Demo
```

La première fois, le chargement prend 5 à 10 minutes ; les fois suivantes, seuls les pointages et transmissions du jour
sont rejoués (lancez-le le matin du rendez-vous). `-Demo -RemiseAZero` repart d'une base neuve, avec 18 mois d'historique
jusqu'à la veille. Ajoutez `-Console` pour montrer aussi la console de l'éditeur.

**Sans PC de démonstration** : depuis la console de l'éditeur, Ordres › Démonstration… crée une instance hébergée
`demo-…` avec ce même jeu de données en quelques minutes. Son adresse s'affiche dans l'ordre ; les comptes sont les
mêmes. Supprimez-la depuis sa fiche après le rendez-vous.

| Accès | Adresse | Compte |
|---|---|---|
| Bureau (directrice) | http://localhost:1420 | `nathalie.kaloi` / `Niaouli-2026!` |
| Tablette d'accueil | http://localhost:1420/kiosk | appairée au chargement ; Maëva T., PIN `2468` ; cuisine : Paulo, PIN `7419` |
| Parents | http://localhost:5180 | `sarah.waheo@exemple.nc`, `kevin.gope@exemple.nc`, `anne.poedi@exemple.nc`… : code dans Mailpit (http://localhost:8025) |

Ouvrez trois onglets avant le rendez-vous : le bureau, la tablette (ou une vraie tablette sur le réseau), l'application
des parents en mode téléphone (F12, mode appareil).

## 1. Le matin à l'accueil (tablette) ★

1. **Arrivées** : pointez un enfant en deux touches. Les allergies s'affichent sur sa carte.
2. **Départ contrôlé** : choisissez une personne non autorisée, le départ est bloqué ; la directrice valide avec son code.
3. **Journée** : un biberon en millilitres, une sieste, une **photo** (tous les enfants photographiés doivent avoir
   l'autorisation : l'enfant sans autorisation n'est pas proposé).
4. **Retard annoncé** par un parent ce matin : il apparaît sur la carte de Noa.
5. **Mon espace** (PIN de Maëva) : son planning, sa demande de congé en attente, le cahier d'équipe.

## 2. Le tableau de bord de la directrice ★

1. **Encadrement en temps réel** par section, qui bouge quand on pointe à la tablette.
2. **Premiers pas** : la liste de mise en route d'une nouvelle crèche (ici presque tout est fait).
3. **Absences signalées** par les parents (Lila, rendez-vous chez le pédiatre) : confirmez.
4. **Demandes d'accueil** : Kevin demande un mercredi pour Lila ; Ti'Cagou montre les places libres et l'encadrement
   prévu. Acceptez : l'accueil s'ajoute au planning et sera facturé.
5. **Alertes** : diplôme SST qui expire, HACCP échu de Paulo, contrat de remplacement de Sandrine qui se termine.

## 3. Les enfants et les familles

1. **Fiche de Noa** : santé (PAI arachide, trousse d'urgence), personnes autorisées, autorisations, documents.
2. **Contrat** : formule « Temps plein » à 98 500 F ; comparez avec le calcul à l'heure ; faites un **avenant**.
3. **Présences** : 18 mois d'historique, absences justifiées, congés.
4. **Journée et portail** : ce que voient les parents.

## 4. Les inscriptions

1. **Tableau des demandes** : de la nouvelle demande au contrat, avec relances et visites.
2. **Pré-inscriptions en ligne** : trois demandes arrivées par le formulaire public, avec les questions de la crèche
   (Paramètres › Pré-inscription en ligne, le lien à mettre sur le site de la crèche).
3. **Capacité** : places libres par section et par mois, pour proposer une date d'entrée.
4. Transformez une demande en **contrat** : le barème et ses formules (temps plein, matins, après-midi, 3 jours).

## 5. La facturation ★

1. **Préparer le mois** : anomalies (absence sans justificatif, départ non pointé), puis générer.
2. **Une facture** : forfait ou formule, heures complémentaires, aides de la province Sud et de la CAFAT déduites, en
   francs CFP, RIB imprimé.
3. **Encaissements** : paiement par virement, chèque ou carte, paiement partiel, **avoir** (journée pédagogique).
4. **Impayés** : relance préparée.
5. **Financeurs** : la demande mensuelle à la province, avec son export.
6. **Statistiques** : 18 mois, deux années à comparer (chiffre d'affaires, occupation, encaissements).
7. **Comptabilité** : export pour le cabinet (CSV, FEC).

## 6. L'équipe

1. **Planning de la semaine** : encadrement prévu, créneaux en sous-effectif.
2. **Demandes** : congé de Maëva à valider (le planning montre l'effet sur l'encadrement), récupération acceptée,
   heures sup.
3. **Feuilles d'heures** des mois passés, validées ; export.
4. **Fiches** : diplômes, formations, alertes d'échéance.

## 7. Les repas

1. **Aujourd'hui en cuisine** : repas comptés d'après les présences, enfants à adapter (allergies, régime, texture).
2. **Menus** de la semaine, publiés aux parents.
3. **Récapitulatif trimestriel** pour les financeurs ; les trimestres passés sont clos.
4. **Températures** : relevés du jour, un hors seuil avec sa mesure corrective.

## 8. Les parents (téléphone) ★

1. Connexion par e-mail : le code arrive dans Mailpit (en vrai, dans la boîte du parent).
2. **La journée** de l'enfant : repas, sieste, change, mots de l'équipe, médicament donné (Hina).
3. **Album** : 8 semaines de photos et de vidéos, téléchargeables.
4. **Actualités** : fête de fin d'année, semaine du goût, journée pédagogique, avec leur affiche.
5. **Messages** : ceux de la crèche, à toutes les familles ou à une seule ; répondez.
6. **Factures** et paiement ; **documents** à signer (autorisation de sortie signée par Sarah).
7. **Signaler une absence**, **demander une journée**, **prévenir d'un retard** : retour au tableau de bord pour les voir.

## 9. Pour finir

1. **Paramètres** : barème et formules, règles d'encadrement datées, financeurs, utilisateurs et droits par rôle,
   sauvegardes chiffrées chaque nuit, tablettes.
2. **Tablettes et abonnement** : une seule formule, tout compris ; seuls les places, PC et tablettes changent.
3. **Reprise des données** depuis Excel, et la reprise offerte.

Questions fréquentes : hors connexion, la tablette continue de pointer ; les données de santé sont visibles seulement
des rôles autorisés, chaque consultation est journalisée ; les montants sont en francs CFP entiers.
