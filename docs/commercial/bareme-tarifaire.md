# Barème tarifaire Ti'Cagou — proposition

[← Wiki](../wiki/README.md)

> Mis à jour pour l'application hébergée (option B). Les hausses et baisses ci-dessous sont des hypothèses à valider.

> **Proposition de départ, à ajuster.** Ces prix sont une base de raisonnement, pas une étude de marché. Avant de les
> publier, présentez-les à 3 à 5 directrices (§ 7) et faites valider le taux de TGC par votre comptable.
> Montants en **francs CFP hors TGC**, arrondis. Parité fixe : 1 € = 119,33 F.

## 1. Les principes

- **Un abonnement annuel** par structure : sa date de fin est celle de la période payée (`creche.sh abonnement`). Payable
  d'avance, mensualisable par prélèvement.
- **Un prix selon la taille** (places agréées) : c'est ce que l'abonnement contrôle (`--places`).
- **Trois formules** : Essentiel, Gestion, Complet ([ADR 0015](../adr/0015-formules-et-modules.md)).
- **Des modules à la carte**, qui s'ajoutent à toute formule. Le logiciel ne montre que ce qui est compris : menus et
  paramètres suivent la formule.
- **Des prestations ponctuelles** facturées à part : mise en route, saisie, formation.
- L'**hébergement**, les **sauvegardes**, les **mises à jour** et le **support standard** sont compris dans l'abonnement : c'est ce qui justifie un paiement
  récurrent plutôt qu'un achat unique.

## 2. Les formules

| Par mois | Micro (jusqu'à 15 places) | Structure (16 à 40) | Grande (41 à 80) |
|---|---|---|---|
| **Essentiel** | 6 500 F | 9 500 F | 14 000 F |
| **Gestion** | 11 500 F | 16 500 F | 23 500 F |
| **Complet** | 18 500 F | 26 000 F | 36 500 F |

Par an, payé d'avance : 12 mois. Au-delà de 80 places ou pour plusieurs structures : sur devis, − 15 % par structure
supplémentaire.

| | Essentiel | Gestion | Complet |
|---|---|---|---|
| Enfants et familles : santé, PAI, autorisations, personnes autorisées | ✔ | ✔ | ✔ |
| Présences des enfants sur tablette, départ contrôlé, hors connexion | ✔ | ✔ | ✔ |
| Présences du personnel | ✔ | ✔ | ✔ |
| Encadrement en temps réel, tableau de bord | ✔ | ✔ | ✔ |
| Hébergement, sauvegardes chiffrées, mises à jour, support standard | ✔ | ✔ | ✔ |
| Inscriptions, liste d'attente, contrats | | ✔ | ✔ |
| Facturation avec aides, encaissements, statistiques | | ✔ | ✔ |
| Comptabilité : export CSV et FEC | | | ✔ |
| Personnel : fiches, diplômes, planning, heures | | | ✔ |
| Repas : menus, fiche cuisine, récapitulatif des aides | | | ✔ |
| Portail parents : journée, photos, messages, factures, signatures | | | ✔ |

Chaque formule comprend **1 PC et 2 tablettes**.

## 3. Modules à la carte (par mois)

Ils s'ajoutent à toute formule. Une formule revient moins cher que ses modules pris séparément : environ 8 % de moins pour
Gestion et 15 % pour Complet.

| Module | Micro | Structure | Grande | A besoin de |
|---|---|---|---|---|
| **Inscriptions et contrats** | 2 000 F | 3 000 F | 4 000 F | |
| **Facturation et statistiques** | 4 000 F | 5 500 F | 7 500 F | Inscriptions et contrats |
| **Comptabilité** | 1 000 F | 1 500 F | 2 000 F | Facturation |
| **Personnel** | 2 500 F | 3 500 F | 5 000 F | |
| **Repas** | 2 000 F | 2 500 F | 3 500 F | |
| **Portail parents** | 3 500 F | 5 000 F | 7 000 F | |

Équipement supplémentaire : **PC** + 1 500 F par mois ; **tablette** + 1 000 F par mois.

Engagement : 1 an, renouvelable. **− 10 %** sur l'abonnement pour un engagement de 3 ans payé annuellement.

## 4. Prestations ponctuelles

| Prestation | Prix | Contenu |
|---|---|---|
| **Mise en route** | 30 000 F | création de la crèche, paramétrage avec la directrice, à distance ou 2 h sur place |
| Routeur 4G de secours | prix fournisseur + 15 % | recommandé : garde le bureau en ligne si la fibre coupe |
| **Saisie initiale** des enfants et familles | 1 800 F par enfant, plafonné à 45 000 F jusqu'à 30 enfants | à partir du fichier de la crèche : fiches, familles, personnes autorisées, santé, contrats en cours |
| **Formation direction** | 30 000 F | 3 h, support remis ([12-formation-direction.md](../wiki/12-formation-direction.md)) |
| **Formation équipes** | 15 000 F par séance | 1 h, jusqu'à 8 personnes ([13-formation-equipe.md](../wiki/13-formation-equipe.md)) |
| Intervention sur site hors contrat | 9 000 F de l'heure | formation complémentaire, réglage du réseau |
| Matériel (si fourni par vous) | prix fournisseur + 15 % | tablettes et supports |

Offre de lancement possible : mise en route offerte pour un engagement de 3 ans.

## 5. Niveaux de support

| | **Standard** (compris) | **Premium** (+ 4 000 F par mois) |
|---|---|---|
| Canal | e-mail, formulaire | e-mail et téléphone |
| Délai de première réponse | 1 jour ouvré | 4 heures ouvrées |
| Prise en main à distance | oui | oui, prioritaire |
| Intervention sur site | facturée (§ 4) | 2 heures par an comprises |
| Engagement de disponibilité | — | à définir une fois l'hébergeur choisi |

## 6. Exemples de devis

**Crèche de 20 places, Essentiel, 1 PC, 2 tablettes**

| Ligne | Mensuel | Annuel |
|---|---|---|
| **Essentiel Structure** | **9 500 F** | **114 000 F** |
| Première année : mise en route, saisie (20 enfants), formation direction | | 30 000 + 36 000 + 30 000 = **96 000 F** |

**Micro-crèche de 12 places, Gestion et Repas, 1 PC, 2 tablettes**

| Ligne | Mensuel | Annuel |
|---|---|---|
| Gestion Micro | 11 500 F | 138 000 F |
| Repas | 2 000 F | 24 000 F |
| **Total abonnement** | **13 500 F** | **162 000 F** |
| Première année : mise en route, saisie (12 enfants), formation direction, 1 séance équipe | | 30 000 + 21 600 + 30 000 + 15 000 = **96 600 F** |

**Crèche de 48 places, Complet, 2 PC, 5 tablettes, support Premium**

| Ligne | Mensuel | Annuel |
|---|---|---|
| Complet Grande | 36 500 F | 438 000 F |
| PC supplémentaire (1) | 1 500 F | 18 000 F |
| Tablettes supplémentaires (3) | 3 000 F | 36 000 F |
| Support Premium | 4 000 F | 48 000 F |
| **Total abonnement** | **45 000 F** | **540 000 F** |
| Première année : mise en route, saisie (48 enfants), formation direction, 2 séances équipes | | 30 000 + 86 400 + 30 000 + 30 000 = **176 400 F** |

TGC à ajouter au taux applicable (pour des prestations informatiques, souvent le taux de 11 % : à confirmer avec votre
comptable).

## 7. Ce que coûte un client (pour vérifier la marge)

| Poste | Ordre de grandeur | Remarque |
|---|---|---|
| Hébergement des crèches et du portail, sauvegardes hors site, e-mails | 12 000 à 18 000 F par mois pour 15 crèches | hypothèse ; 3 à 4 fois plus si l'hébergement certifié est exigé |
| Test d'intrusion | 180 000 à 360 000 F par an | hypothèse : un tous les deux ans |
| Assurance RC professionnelle et cyber | à demander à votre assureur | |
| Support | 0,75 h par client et par mois, hypothèse | à mesurer et suivre |
| Exploitation du serveur | 4 h par mois | |
| Mises à jour et corrections | votre temps de développement | |

**Attention à Essentiel Micro.** À 6 500 F par mois, il ne couvre pas 0,75 h de support à 9 000 F de l'heure (6 750 F) plus
l'hébergement. C'est un prix d'appel : il ne tient que si ces crèches demandent peu de support ou passent à Gestion.
Gestion et Complet font la marge.

## 8. Comment valider ces prix

1. Faites une démonstration à 3 à 5 directrices (micro-crèche, crèche associative, crèche municipale, crèche
   d'entreprise) sur une crèche de démonstration.
2. Demandez-leur ce qu'elles utilisent aujourd'hui (tableur, cahier, logiciel métropolitain) et combien elles paient,
   y compris le temps passé à facturer. Repère : Meeko affiche 99 € HT par mois, soit environ 11 800 F.
3. Montrez la grille : si toutes l'acceptent sans discuter, elle est trop basse ; si toutes refusent, trop haute.
4. Proposez à une crèche pilote 6 mois à − 50 % contre un retour régulier et un témoignage.
