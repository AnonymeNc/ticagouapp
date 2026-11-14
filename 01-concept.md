# Concept fonctionnel — Ti'Cagou

## Cible
- Crèches et crèches-garderies agréées (cœur de cible).
- Extensions : garderies, accueils périscolaires, centres de vacances, micro-structures.
- Utilisateurs : directrice / gérant(e), auxiliaires et éducatrices, cuisine, comptable, parents.

## Promesse
> Tout le quotidien de la crèche dans un seul outil : présences, encadrement, repas, facturation et aides,
> dossiers des enfants et du personnel — conforme aux règles calédoniennes, en ligne, sans rien à installer ni à sauvegarder.

## Domaines fonctionnels

Une seule formule, tout compris ([ADR 0021](docs/adr/0021-formule-unique.md)).

1. **Enfants & familles** : dossier enfant (identité, section, allergies, régime, PAI, vaccins, médecin), responsables
   légaux, **personnes autorisées** à récupérer l'enfant (avec photo), contacts d'urgence, autorisations, documents
   chiffrés, export et purge des données d'une famille.
2. **Inscriptions & liste d'attente** : demandes (dont pré-inscription en ligne), priorités, capacité, passage en contrat.
3. **Contrats d'accueil** : barème, planning, mensualisé ou au réel, adaptation, avenants, PDF.
4. **Pointage** sur tablette (navigateur ou application Ti'Cagou Pointage) : arrivée/départ en 2 touches, contrôle de
   la personne qui récupère, départ non autorisé validé sur place ou à distance, hors connexion.
5. **Taux d'encadrement en temps réel** par section, règles datées, alerte sur le téléphone de la direction.
6. **Repas** : comptage, régimes et allergies en cuisine, menus, récapitulatif trimestriel pour l'aide aux repas.
7. **Facturation mensuelle** : forfait, heures complémentaires, absences, ventilation **parent / aides publiques /
   employeur**, factures et avoirs PDF, encaissements, relances, attestations, export comptable.
8. **Personnel** : fiches, diplômes et échéances, planning, congés, feuilles d'heures (export paie).
9. **Tableau de bord et statistiques** : présences, alertes, remplissage, chiffre d'affaires.
10. **Portail et application des parents** : journée de l'enfant, photos et vidéos, messages, absences et retards,
    factures, documents à signer, notifications.
11. **Application Direction** : la crèche sur le téléphone de la direction (encadrement, présences, fiches, départs à
    valider, notifications).
12. **Sauvegardes** chiffrées, journal d'audit, double authentification.

Pistes pour la suite : préparation de la paie, périscolaire et centres de vacances (inscription à la séance), rapports
pour l'agrément, envoi des factures et relances par e-mail, paiement en ligne.

## Spécificités NC — ⚠️ à vérifier auprès de la DPASS / Province Sud et d'un expert-comptable

| Sujet | Ce qu'on sait | À vérifier |
|---|---|---|
| Agrément | Les crèches sont agréées par la province (DPASS en Province Sud) | Conditions, documents demandés, contrôles → rapports à prévoir |
| Taux d'encadrement | Réglementé | Ratios exacts par âge, règles de qualification du personnel → **paramétrable** |
| Aide aux repas | Aide forfaitaire par repas délivré, établie par trimestre (montant 2019 : 369 F) | Montant actuel, justificatifs exigés |
| Aides aux familles | Plusieurs dispositifs provinciaux (dont mesures 2026 en Province Sud) | Lesquels passent par la crèche (déduction sur facture) ou sont versés aux familles |
| Employeurs | Certains employeurs participent aux frais de garde dans des crèches conventionnées | Format des attestations / factures attendues |
| Facturation | XPF sans décimales ; TGC | Régime de TGC applicable à l'accueil de jeunes enfants (exonération ou non) |
| Charges sociales | Le secteur a bénéficié d'abattements de cotisations | Utile pour la future préparation de la paie |
| Données | Données de santé d'enfants | Durées de conservation, consentements, sécurité |

Toutes les règles (ratios, montants d'aides, taux) sont **paramétrables avec dates de validité**, jamais en dur.

## Questions à poser aux crèches pilotes
1. Comment facturez-vous aujourd'hui (Excel, logiciel, papier) ? Combien de temps par mois ?
2. Quelles aides passent par vous, et quels justificatifs devez-vous produire ?
3. Comment suivez-vous le taux d'encadrement au quotidien ?
4. Avez-vous une tablette / un PC fixe à l'accueil ? Une connexion internet fiable ?
5. Les familles utiliseraient-elles l'application des parents ? La direction, l'application sur son téléphone ?
6. Qu'est-ce qui vous fait perdre le plus de temps ?
