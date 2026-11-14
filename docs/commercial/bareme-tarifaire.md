# Barème tarifaire Ti'Cagou — proposition

[← Wiki](../wiki/README.md)

> Mis à jour pour l'application hébergée (option B). Les hausses et baisses ci-dessous sont des hypothèses à valider.

> **Proposition de départ, à ajuster.** Ces prix sont une base de raisonnement, pas une étude de marché. Avant de les
> publier, présentez-les à 3 à 5 directrices (§ 7) et faites valider le taux de TGC par votre comptable.
> Montants en **francs CFP hors TGC**, arrondis. Parité fixe : 1 € = 119,33 F.

## 1. Les principes

- **Un abonnement annuel** par structure : sa date de fin est celle de la période payée (`creche.sh abonnement`). Payable
  d'avance, mensualisable par prélèvement.
- **Un seul prix, 15 000 F par mois**, aligné sur Meeko (99 € HT, ≈ 11 800 F) avec ce que Meeko n'a pas : un éditeur sur
  place, en Nouvelle-Calédonie, et le **support rapide compris**. Les places agréées restent dans l'abonnement (`--places`)
  pour le contrôle de la capacité, pas pour le prix.
- **Une seule formule, tout compris** ([ADR 0021](../adr/0021-formule-unique.md)) : toutes les fonctions de Ti'Cagou,
  pour toutes les crèches. Rien à choisir, rien à ajouter.
- **Des prestations ponctuelles** facturées à part : mise en route, formation, saisie depuis le papier.
- **La reprise des données est offerte** depuis un fichier numérique (§ 4) : changer de logiciel ne coûte rien de plus.
- L'**hébergement**, les **sauvegardes**, les **mises à jour** et le **support standard** sont compris dans l'abonnement : c'est ce qui justifie un paiement
  récurrent plutôt qu'un achat unique.

## 2. La formule Ti'Cagou

| Par mois | Jusqu'à 80 places |
|---|---|
| **Ti'Cagou, tout compris, support rapide compris** | **15 000 F HT** |

Par an, payé d'avance : 180 000 F HT. Au-delà de 80 places ou pour plusieurs structures : sur devis, − 15 % par
structure supplémentaire. Barème en vigueur depuis le 01/11/2026 (console : « Barème formule unique »).

Tout est compris : enfants et familles (santé, PAI, autorisations, personnes autorisées), présences des enfants et du
personnel sur tablette (départ contrôlé, hors connexion), encadrement en temps réel, inscriptions et pré-inscription en
ligne, contrats, facturation avec aides et encaissements, statistiques, comptabilité (CSV et FEC), personnel (fiches,
diplômes, planning, heures, espace pro), repas (menus, fiche cuisine, températures), portail parents (journée, album
photos et vidéos, messages, factures, signatures), hébergement, sauvegardes chiffrées, mises à jour, **support rapide**
(éditeur joignable en Nouvelle-Calédonie, aux heures de la crèche).

La formule comprend **1 PC et 2 tablettes**.

## 3. Options (par mois)

Équipement supplémentaire : **PC** + 1 500 F par mois ; **tablette** + 1 000 F par mois. Le support rapide est compris.
Les applications mobiles sont comprises : jusqu'à 10 téléphones de la direction, sans compter dans les PC
([ADR 0028](../adr/0028-postes-mobiles-et-abonnement.md)) ; l'application des parents est gratuite pour les familles.

Engagement : 1 an, renouvelable. **− 10 %** sur l'abonnement pour un engagement de 3 ans payé annuellement.

## 4. Prestations ponctuelles

| Prestation | Prix | Contenu |
|---|---|---|
| **Mise en route** | 30 000 F | création de la crèche, paramétrage avec la directrice, à distance ou 2 h sur place |
| Routeur 4G de secours | prix fournisseur + 15 % | recommandé : garde le bureau en ligne si la fibre coupe |
| **Reprise des données** depuis Excel ou Meeko | **offerte** | une reprise par crèche, avec une passe de correction : familles, responsables, enfants, personnes autorisées, santé, autorisations |
| **Saisie depuis des dossiers papier** | 1 800 F par enfant, plafonné à 45 000 F jusqu'à 30 enfants | mêmes données, saisies à partir des dossiers de la crèche |
| **Formation direction** | 30 000 F | 3 h, support remis ([12-formation-direction.md](../wiki/12-formation-direction.md)) |
| **Formation équipes** | 15 000 F par séance | 1 h, jusqu'à 8 personnes ([13-formation-equipe.md](../wiki/13-formation-equipe.md)) |
| Intervention sur site hors contrat | 9 000 F de l'heure | formation complémentaire, réglage du réseau |
| Matériel (si fourni par vous) | prix fournisseur + 15 % | tablettes et supports |

Offre de lancement possible : mise en route offerte pour un engagement de 3 ans.

**Ce que couvre la reprise offerte.** La crèche remplit le modèle Excel de Ti'Cagou ou y copie son fichier, puis le dépose
elle-même dans son instance : le fichier ne passe pas par e-mail. La direction relit les allergies, les PAI et les
personnes autorisées avant de valider ([ADR 0016](../adr/0016-reprise-des-donnees.md)).

- **Non repris :** les contrats en cours (ressaisis avec l'assistant de contrat), les anciennes factures (à conserver en
  PDF : la numérotation Ti'Cagou commence à la mise en service), les pointages passés, les photos et les documents joints.
- **Meeko :** ne pas l'annoncer tant qu'un export réel n'a pas été repris avec succès. Son format n'est pas encore connu.

## 5. Niveaux de support

Un seul niveau, **compris dans la formule** : c'est l'avantage d'un éditeur calédonien.

| | **Support rapide** (compris) |
|---|---|
| Canal | téléphone, WhatsApp, e-mail (coordonnées affichées dans l'aide de l'application) |
| Délai de première réponse | 4 heures ouvrées, heure de Nouméa |
| Prise en main à distance | oui |
| Intervention sur site | facturée (§ 4) |
| Engagement de disponibilité | à définir une fois l'hébergeur choisi |

## 6. Exemples de devis

**Crèche de 20 places, 1 PC, 2 tablettes**

| Ligne | Mensuel | Annuel |
|---|---|---|
| **Ti'Cagou** | **15 000 F** | **180 000 F** |
| Première année : mise en route, formation direction (reprise des données offerte) | | 30 000 + 30 000 = **60 000 F** |

**Crèche de 48 places, 2 PC, 5 tablettes, engagement 3 ans**

| Ligne | Mensuel | Annuel |
|---|---|---|
| Ti'Cagou | 15 000 F | 180 000 F |
| PC supplémentaire (1) | 1 500 F | 18 000 F |
| Tablettes supplémentaires (3) | 3 000 F | 36 000 F |
| Remise engagement 3 ans (− 10 %) | − 1 950 F | − 23 400 F |
| **Total abonnement** | **17 550 F** | **210 600 F** |
| Première année : mise en route, formation direction, 2 séances équipes (reprise des données offerte) | | 30 000 + 30 000 + 30 000 = **90 000 F** |

Ces exemples supposent un fichier numérique ; depuis le papier, ajouter la saisie (§ 4).

TGC à ajouter au taux applicable (pour des prestations informatiques, souvent le taux de 11 % : à confirmer avec votre
comptable).

## 7. Ce que coûte un client (pour vérifier la marge)

| Poste | Ordre de grandeur | Remarque |
|---|---|---|
| Hébergement des crèches et du portail, sauvegardes hors site, e-mails | 12 000 à 18 000 F par mois pour 15 crèches | hypothèse ; 3 à 4 fois plus si l'hébergement certifié est exigé |
| Test d'intrusion | 180 000 à 360 000 F par an | hypothèse : un tous les deux ans |
| Assurance RC professionnelle et cyber | à demander à votre assureur | |
| Support | 0,75 h par client et par mois, hypothèse | à mesurer et suivre |
| Reprise des données offerte | votre temps d'aide au remplissage du modèle | à mesurer : 12 minutes par enfant équivalent au tarif de saisie, à 9 000 F de l'heure |
| Exploitation du serveur | 4 h par mois | |
| Mises à jour et corrections | votre temps de développement | |

**Marge à surveiller.** À 15 000 F par mois, une crèche couvre 0,75 h de support à 9 000 F de l'heure (6 750 F) et sa part
d'hébergement, mais le support rapide compris est la promesse : à mesurer chaque mois dans la console (Reporting ›
Rentabilité par crèche). Une crèche qui dépasse durablement 1 h de support par mois doit être regardée de près.

## 8. Comment valider ces prix

1. Faites une démonstration à 3 à 5 directrices (micro-crèche, crèche associative, crèche municipale, crèche
   d'entreprise) sur une crèche de démonstration.
2. Demandez-leur ce qu'elles utilisent aujourd'hui (tableur, cahier, logiciel métropolitain) et combien elles paient,
   y compris le temps passé à facturer. Repère : Meeko affiche 99 € HT par mois, soit environ 11 800 F.
3. Montrez la grille : si toutes l'acceptent sans discuter, elle est trop basse ; si toutes refusent, trop haute.
4. Proposez à une crèche pilote 6 mois à − 50 % contre un retour régulier et un témoignage.
