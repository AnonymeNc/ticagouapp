# Reprendre les données d'une crèche

[← Wiki](README.md)

La reprise est offerte depuis un fichier numérique ([barème § 4](../commercial/bareme-tarifaire.md#4-prestations-ponctuelles)).
Elle se fait une fois, sur une crèche neuve, après le paramétrage des sections. Décisions : [ADR 0016](../adr/0016-reprise-des-donnees.md).

## Ce qui est repris

| Repris | Non repris |
|---|---|
| Familles et responsables | Contrats en cours : à ressaisir avec l'assistant de contrat |
| Enfants, avec section et dates d'entrée et de sortie | Factures passées : à conserver en PDF |
| Santé : régime, PAI, médecin, consignes, allergies, vaccins | Pointages passés |
| Personnes autorisées | Photos et documents joints |
| Autorisations : photos, sorties, soins d'urgence | Pièces d'identité : à vérifier à la crèche |

## Les étapes

1. Connectée à son instance, la direction ouvre **Paramètres › Reprise des données** et télécharge le modèle.
2. Elle remplit le classeur ou y copie les colonnes de son fichier. Vous pouvez l'aider en partage d'écran : **le fichier ne
   passe pas par e-mail**, il contient des données de santé.
3. Elle dépose le fichier et clique sur **Analyser le fichier**. Rien n'est écrit à ce stade.
4. Elle corrige les erreurs dans le classeur, l'enregistre, le choisit de nouveau et relance l'analyse. Une seule erreur
   bloque tout le fichier.
5. Elle lit les points à vérifier, notamment les colonnes et les feuilles ignorées : elles ne seront pas reprises.
6. Elle relit chaque enfant (allergies, PAI, personnes pour le départ), coche la relecture, puis importe.
7. Elle ressaisit les contrats en cours et supprime le fichier de son poste.

## Remplir le classeur

- Les colonnes marquées d'une étoile sont obligatoires. Les feuilles Familles, Responsables et Enfants sont indispensables.
- `Réf famille` et `Réf enfant` sont libres (F1, E1…) : elles relient les feuilles et ne sont pas conservées.
- Dates au format JJ/MM/AAAA, ou de vraies dates Excel. Une année sur deux chiffres est refusée.
- `oui` ou `non` dans les colonnes concernées.
- La section doit porter le nom d'une section de la crèche, rappelé dans la feuille Lisez-moi.

## Les erreurs qui reviennent

| Message | Quoi faire |
|---|---|
| Gravité : obligatoire | Demander la gravité à la direction : Ti'Cagou n'en choisit jamais une par défaut. |
| « Aucune » n'est pas une allergie | Supprimer la ligne : un enfant sans allergie n'a pas de ligne dans cette feuille. |
| PAI : indiquez oui ou non | La ligne décrit un PAI : confirmer s'il est en cours. |
| Cette famille n'a aucun responsable légal | Indiquer `oui` pour au moins un responsable. |
| Cet enfant existe déjà dans Ti'Cagou | Retirer sa ligne, ou corriger sa fiche à l'écran. |
| Format .xls ou protégé par un mot de passe | Enregistrer le classeur en .xlsx, sans mot de passe. |

## Depuis Meeko

Le format des exports Meeko n'est pas encore connu : aucun export réel n'a été essayé. En attendant, copiez les colonnes de
l'export dans le modèle. Avant de promettre une reprise directe, faites l'essai sur un export réel, anonymisé ou limité à de
fausses données.
