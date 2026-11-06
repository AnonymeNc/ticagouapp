# ADR 0003 — Documents joints chiffrés, export et purge des familles

- **Statut** : acceptée
- **Date** : 31/10/2026

## Contexte

Le dossier d'un enfant contient des pièces sensibles : PAI, carnet de vaccination, autorisations signées,
justificatifs, photos de l'enfant et des personnes autorisées. CLAUDE.md impose des données de santé
protégées, un journal des consultations, l'export et la purge des données d'une famille.

## Décision

**Stockage**
- Les fichiers sont écrits sur le disque du serveur, sous `ticagou.storage.dir` (défaut `data/documents`),
  dans `<structure>/<année>/<mois>/<uuid>.bin` : aucun nom d'origine ni donnée personnelle dans le chemin.
- Chiffrement **AES-256-GCM** (JDK, sans dépendance) : IV aléatoire de 12 octets par fichier, en-tête `TCG1`,
  identifiant du fichier en données associées (un contenu ne peut pas être substitué à un autre).
  L'empreinte SHA-256 du contenu en clair est contrôlée à chaque lecture.
- Clé : `ticagou.storage.key` (Base64, 32 octets) si fournie, sinon générée au premier démarrage et
  conservée dans `server_secret`. Copier le dossier des documents seul ne permet donc pas de les lire.
- La base ne garde que les métadonnées (`stored_file`). Écriture et suppression suivent la transaction :
  un fichier écrit lors d'une transaction annulée est effacé, un fichier supprimé ne disparaît qu'après validation.
- Le type est déduit des premiers octets (PDF, PNG, JPEG), jamais du type annoncé par le client.
  Limites : photos 2 Mo, documents 10 Mo.

**Accès** (maquette Paramètres › Droits par rôle)
- Direction : lecture et modification ; Éducatrice : consultation ; Cuisine : liste avec allergies et régimes
  uniquement ; Comptabilité : aucun accès au module Enfants.
- Journal des consultations (`child_record_access`) : ouverture de la fiche (`FICHE`), téléchargement d'un
  document (`DOCUMENTS`), export (`EXPORT`).

**Export et purge**
- Export : un ZIP lisible sans Ti'Cagou — `famille.json` (foyer, responsables, fiches complètes) et, par
  enfant, photo, photos des personnes autorisées et documents déchiffrés. Tracé (`FAMILY_EXPORTED` + une
  consultation `EXPORT` par enfant).
- Purge : direction uniquement ; saisie du nom de la famille pour confirmer ; refusée tant qu'un enfant n'a
  pas de date de sortie passée. Supprime foyer, responsables, enfants, santé, autorisations, personnes
  autorisées, documents et fichiers. Le journal garde `FAMILY_PURGED` avec des identifiants et des comptes
  seulement ; les traces de consultation restent (elles ne contiennent que l'identifiant de l'enfant).

## Alternatives écartées

- **Documents en base (bytea)** : alourdit sauvegardes et requêtes ; le chiffrement au repos resterait à faire.
- **Chiffrement du disque (BitLocker)** : utile en complément, mais ne protège pas une copie des fichiers ni une
  sauvegarde emportée.
- **Bibliothèque de chiffrement tierce (Tink…)** : le JDK suffit pour AES-GCM ; pas de dépendance lourde.

## Conséquences

- **La clé doit être sauvegardée avec la base** (elle y est par défaut) : sans elle, les documents sont perdus.
  Le module Sauvegardes devra chiffrer la base et le dossier des documents ensemble.
- La purge n'efface pas encore les futures factures (à conserver légalement) : le module Facturation devra
  anonymiser plutôt que supprimer.
- Pas de rotation de clé pour l'instant (format `TCG1` prévu pour la versionner).
