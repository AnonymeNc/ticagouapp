# Démonstrations par formule

[← Wiki](../wiki/README.md)

Chaque démonstration part du même jeu de données : « Les P'tits Niaoulis », 24 enfants, 3 mois d'historique, cinq familles
sur le portail. La formule change seulement ce qui est visible. Comptez 15 minutes.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\tester-en-local.ps1 -Demo -Formule Essentiel
```

Remplacez `Essentiel` par `Gestion` ou `Complet`. La relance garde les données et change la formule. Une formule sans
portail efface ce que le portail détient, comme chez un client qui retire le module : revenir en Complet recharge tout.

| Accès | Adresse | Compte |
|---|---|---|
| Bureau | http://localhost:1420 | `nathalie.kaloi` / `Niaouli-2026!` |
| Tablette | http://localhost:1420/kiosk | code de la tablette, puis Maëva T., PIN `2468` |
| Parents | http://localhost:5180 | `sarah.waho@exemple.nc`, lien de connexion dans Mailpit (http://localhost:8025) |

## Essentiel : « Je sais qui est là, à chaque instant »

Pour une micro-crèche qui veut quitter le cahier de présence.

1. **Tablette** : pointez une arrivée en deux touches, puis un départ.
2. Essayez un départ avec une personne non autorisée : il est bloqué, et la direction valide avec son code.
3. **Tableau de bord** : l'encadrement change en direct, les allergies des enfants présents s'affichent.
4. **Fiche enfant** : santé, PAI, personnes autorisées.
5. **Paramètres** : six rubriques seulement. Le logiciel reste simple parce qu'il ne montre que ce qui est acheté.

## Gestion : « Mes factures sont faites en une matinée »

Pour une crèche qui facture sur tableur.

1. **Inscriptions** : une demande, la liste d'attente, sa conversion en contrat.
2. **Facturation** : préparer le mois, voir les anomalies, générer les factures.
3. Ouvrez une facture : les aides sont déduites, en francs CFP.
4. **Encaissements** : un paiement, une relance.
5. **Statistiques** : remplissage et chiffre d'affaires.

## Complet : « Les parents suivent la journée sur leur téléphone »

Pour une crèche qui veut tout au même endroit.

1. **Tablette** : bouton Journée, un biberon en millilitres, puis un médicament.
2. **Application des parents** : la journée de Hina, avec biberons et médicament.
3. Les parents signent l'autorisation de sortie de Noa, et lisent le message de la crèche.
4. **Personnel** : le planning et les heures du mois.
5. **Repas** : la fiche cuisine avec les allergies du jour.
6. **Comptabilité** : l'export pour le cabinet.

## Conclure

Montrez Paramètres › Tablettes et abonnement : ce qui est compris, ce qui ne l'est pas. Un module s'ajoute à tout moment,
sans réinstallation ni perte de données.
