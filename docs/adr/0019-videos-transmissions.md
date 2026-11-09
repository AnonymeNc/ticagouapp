# ADR 0019 — Vidéos de la journée

- **Statut** : acceptée
- **Date** : 10/11/2026
- Complète l'[ADR 0010](0010-portail-parents-flux-de-donnees.md) (portail parents) et l'[ADR 0018](0018-notifications-push-portail-parents.md) (notifications).

## Contexte

Le lot 1 de l'application parents ([plan](../plan-alignement-meeko.md)) prévoit de courtes vidéos en plus des
photos, comme le font les concurrents. Contraintes :
- la synchronisation avec le portail transporte les fichiers en Base64 dans des lots de 8 Mo (ADR 0010) ;
- l'accueil a une connexion modeste, parfois partagée avec tout le bureau ;
- pas de dépendance lourde (un encodeur vidéo comme ffmpeg.wasm pèse plus de 20 Mo) ;
- une vidéo d'enfant est une donnée personnelle sensible : droit à l'image, durée de conservation courte.

## Décision

**Durée : 30 secondes au plus.** L'enregistrement s'arrête seul à 30 s. Une vidéo de crèche montre un moment
(une chanson, des premiers pas), pas une activité entière.

**Compression à l'enregistrement, sur la tablette** : la vidéo est filmée dans l'application (caméra arrière,
`getUserMedia` + `MediaRecorder`) en 640×480 au plus, environ 900 kbit/s pour l'image et 64 kbit/s pour le son. Une
vidéo de 30 s pèse ainsi 3,5 à 4 Mo. On ne passe pas par l'appareil photo du système, qui filmerait en haute
définition (60 Mo pour 30 s) sans moyen de compresser ensuite.

**Format** : MP4 (H.264) quand la tablette sait l'enregistrer (iPad, Chrome récent), sinon WebM. Le serveur vérifie
le type réel du fichier (signature MP4 ou WebM) et refuse au-delà de **5 Mo** : en Base64, une vidéo tient dans un lot.
Une vidéo WebM ne se lit pas sur les iPhone les plus anciens ; les tablettes récentes enregistrent en MP4.

**En ligne seulement**, comme les photos : une vidéo n'attend pas dans la file hors connexion de la tablette.

**Droit à l'image** : la même autorisation que les photos (« Autorisation de photographier », prises de vue). Une vidéo
n'est partagée avec les familles que si l'éducatrice le demande et si chaque enfant filmé a l'autorisation ; sinon
elle reste dans le journal de la crèche.

**Conservation : 60 jours**, comme les photos.
- Sur le portail, la tâche de rétention efface les transmissions après 60 jours (ADR 0010).
- Sur le serveur de la crèche, la vidéo elle-même est effacée après 60 jours ; la transmission reste dans le journal
  (date, enfants, auteur) sans son fichier. Les photos ne changent pas.
- Les parents peuvent télécharger une vidéo depuis l'album pour la garder.

**Lecture** : le portail sert les vidéos par plages (`Range`), indispensable à Safari sur iPhone. Une nouvelle
vidéo partagée notifie les parents comme une photo (« Nouvelle vidéo de Léa », catégorie Photos).

## Conséquences

- Pas de nouvelle dépendance ; le format de synchronisation ne change pas : une vidéo part dans les champs `photo` et
  `photoType` d'un élément `ENTRY` de type `VIDEO`.
- Une vidéo de 5 Mo occupe un lot de synchronisation à elle seule : quelques secondes de plus avant l'arrivée chez les
  parents.
- Une tablette sans caméra ou sans `MediaRecorder` ne propose pas la vidéo.
