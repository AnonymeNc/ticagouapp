# ADR 0016 — Reprise des données depuis un classeur Excel

- **Statut** : acceptée
- **Date** : 06/11/2026

## Contexte

La reprise des données est offerte aux crèches qui quittent un tableur ou un autre logiciel (barème, § 4). Pour qu'elle reste
tenable, elle ne doit pas être une ressaisie. Les fichiers contiennent des données de santé d'enfants : ils ne doivent pas
circuler par e-mail, et une allergie mal reprise ou une personne autorisée oubliée met un enfant en danger.

## Décision

- La crèche remplit un **classeur modèle** (Paramètres › Reprise des données), une feuille par type de données, reliées par
  des références libres (`Réf famille`, `Réf enfant`). Sont repris : familles, responsables, enfants, santé, allergies,
  vaccins, personnes autorisées, autorisations. Cette reprise relève de la base : aucun module requis.
- La direction **dépose le fichier dans sa propre instance**. Il est lu en mémoire, jamais écrit sur disque ni en base ;
  seule son empreinte SHA-256 est gardée, dans le journal d'audit.
- L'import se fait en **deux temps** : un aperçu qui n'écrit rien (erreurs, avertissements, liste de relecture), puis une
  validation qui renvoie le même fichier avec son empreinte. La validation relit le fichier et refuse s'il a changé.
- **Relecture obligatoire** : la validation exige que la direction déclare avoir relu les allergies, les PAI et les
  personnes autorisées. La déclaration est tracée (`DATA_IMPORTED`, avec les nombres repris, sans donnée nominative).
- **Tout ou rien** : une seule transaction, à travers les services existants. Mêmes règles et même journal qu'une saisie
  à l'écran. La moindre erreur bloque tout le fichier.
- **Rien n'est deviné sur la santé** : la gravité d'une allergie est obligatoire (aucune valeur par défaut), « Aucune » n'est pas
  un allergène, et un PAI renseigné sans « oui » ou « non » est refusé. Les pièces d'identité ne sont jamais marquées vérifiées.
- Un enfant déjà présent (même nom, prénom et date de naissance) est une erreur : un second import ne crée pas de doublons.
- La lecture utilise **fastexcel-reader**, version fixée avec fastexcel (ADR 0006). Il ajoute aalto-xml et commons-compress,
  sans Apache POI. Les `.xls` et les classeurs protégés par mot de passe sont refusés avec un message clair.

## Alternatives écartées

- **Script d'exploitation** (`creche.sh`) : le fichier passerait par l'exploitant, et la direction ne verrait rien avant l'import.
- **Stocker le fichier entre l'aperçu et la validation** : plus simple pour l'interface, mais des données de santé en clair
  sur le serveur, à purger. Renvoyer le fichier coûte quelques centaines de Ko.
- **Importer les lignes valides et ignorer les autres** : la direction croirait la reprise complète alors qu'il manque des
  enfants ou des allergies.
- **Apache POI** : écarté pour les mêmes raisons qu'à l'ADR 0006.

## Conséquences

- Hors périmètre : contrats en cours (ressaisis avec l'assistant : barème, planning et aides à choisir), factures passées,
  pointages passés, photos et documents.
- Meeko et les autres logiciels : le format de leurs exports n'est pas connu. La lecture produit une structure neutre
  (`ImportFile`) : un export réel s'y branchera par une correspondance de colonnes, sans toucher au contrôle ni à l'écriture.
- Un enfant d'une famille déjà présente ne peut pas être rattaché par l'import : le classeur crée toujours ses familles.
  La reprise se fait une fois, sur une crèche neuve ; ensuite, les fiches se corrigent à l'écran.
