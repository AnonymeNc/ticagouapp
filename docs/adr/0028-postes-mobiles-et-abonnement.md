# ADR 0028 — Téléphones de la direction et abonnement

- **Statut** : acceptée (14/11/2026)
- **Date** : 14/11/2026
- **Modifie** : [ADR 0011](0011-licence-lecture-seule-postes.md) (postes comptés), [ADR 0021](0021-formule-unique.md) (ce qui différencie deux abonnements)
- **S'appuie sur** : [ADR 0024](0024-authentification-applications.md)

## Contexte

L'abonnement limite le nombre de **PC** (ADR 0011, 0021) : chaque navigateur tire un identifiant (`X-Ticagou-Poste`),
enregistré à la connexion ; au-delà de la limite, un nouveau poste est refusé (`license_desktops`). L'application
Direction tourne aussi sur téléphone et tablette. Faut-il qu'un téléphone de directrice prenne une place de PC ?

## Décision

**Un téléphone ou une tablette qui ouvre l'application Ti'Cagou Direction ne compte pas comme un PC.**

- Les postes ont désormais un **type** : `BUREAU` (navigateur, comme avant) ou `MOBILE` (application Direction sur iOS
  ou Android, reconnue par l'en-tête `X-Ticagou-App: direction/… (ios|android)`, ADR 0024).
- Les postes `MOBILE` ne sont pas comptés dans les PC de l'abonnement. Ils sont enregistrés, nommés (« iPhone de
  Sandrine »), listés dans Paramètres › Postes et révocables par la direction, exactement comme les PC.
- **Plafond contre l'abus** : 10 postes mobiles actifs par crèche (`ticagou.license.mobile-max`, réglable), au-delà un
  nouveau téléphone est refusé (`license_mobiles`) avec le même message d'action que pour les PC. Un téléphone absent
  depuis 90 jours libère sa place (même règle que `desktop-idle`).
- Un navigateur ouvert sur un téléphone (sans l'application) reste un poste `BUREAU` : seule l'application est reconnue.
- Les tablettes de pointage (`device`, ADR 0005) gardent leur propre compteur, inchangé.
- La lecture seule (échéance dépassée, ADR 0011) s'applique aux téléphones comme aux PC.

## Alternatives écartées

- **Un téléphone = un PC** : une directrice avec un PC et un téléphone consommerait deux places ; l'argument « Ti'Cagou
  dans la poche » deviendrait un supplément.
- **Un compteur « mobiles » vendu dans l'abonnement** : une ligne de plus à vendre et à régler dans la console, pour une
  offre qui se veut unique (ADR 0021).

## Conséquences

- Migration : colonne `desktop_client.kind` (`BUREAU` par défaut) ; `DesktopClientService.active` ne compte que les
  `BUREAU` ; un compteur séparé pour les `MOBILE`.
- L'en-tête `X-Ticagou-App` n'est pas une preuve : un navigateur trafiqué pourrait se faire passer pour un téléphone et
  échapper au compte des PC, dans la limite du plafond mobile. Comme l'ADR 0011, le contrôle protège contre l'oubli et
  l'abus ordinaire, pas contre une attaque délibérée.
- Argumentaire commercial : les applications Direction sont comprises dans l'abonnement, sans place à acheter.
