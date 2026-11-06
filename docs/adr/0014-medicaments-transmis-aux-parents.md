# ADR 0014 — Médicaments donnés, transmis aux parents

- **Statut** : acceptée
- **Date** : 05/11/2026
- **Modifie** : ADR 0010 (aucune donnée de santé sur le portail sans partage de la direction)

## Contexte

Les parents doivent savoir quel médicament leur enfant a reçu à la crèche, et à quelle heure. L'ADR 0010 tenait les
consignes médicamenteuses hors du portail, sauf partage décidé par la direction, et `JournalRules` refusait tout détail
médical. Noter un médicament dans un « Mot » libre aurait contourné ces règles sans structure ni contrôle.

## Décision

- Nouveau type de transmission `MEDICAMENT` : nom du médicament (obligatoire), dose (facultative), heure, précision.
- **Un seul enfant par transmission.** Une transmission à plusieurs enfants paraît chez toutes les familles concernées :
  un médicament y serait lu par d'autres parents. Le serveur refuse, la tablette l'explique.
- Saisi par les personnes qui pointent (direction, éducatrices), comme les autres transmissions.
- Partagé par défaut, décochable sur la tablette. La catégorie « transmissions » de Paramètres › Portail parents
  le coupe aussi.
- Ce qui sort se limite au médicament donné. Allergies, PAI, régime et consignes restent couverts par l'ADR 0010.
- Conservé 60 jours sur le portail, comme les autres transmissions. Il suit l'export et la purge d'une famille.

Toute transmission repas ou médicament accepte une heure antérieure dans la journée, pour une saisie faite après coup.
Le biberon porte une quantité facultative en millilitres.

## Conséquences

- **Le portail reçoit désormais des données de santé par défaut.** La question de l'hébergement des données de santé
  (ADR 0013) porte donc aussi sur lui, et l'information des familles doit le mentionner.
- Ce n'est ni un registre d'administration, ni un contrôle de l'ordonnance : le logiciel ne vérifie pas que la crèche
  a une ordonnance et l'accord des parents.
