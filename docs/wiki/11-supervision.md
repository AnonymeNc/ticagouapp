# Supervision des crèches

[← Sommaire](README.md)

Chaque instance envoie toutes les 15 minutes un état technique au service de supervision, qui vous écrit à
`PORTAL_SUPERVISION_EMAIL` à l'ouverture et à la fermeture d'une alerte, avec un récapitulatif à 7 h. Décisions :
[ADR 0012](../adr/0012-supervision-a-distance.md) et [ADR 0013](../adr/0013-web-heberge-instance-par-creche.md).

Aucun nom ni aucune donnée d'enfant, de famille ou du personnel n'est envoyé. Mentionnez ce service dans le contrat.

## Mise en route

Rien à faire par crèche : quand le portail tourne (`./creche.sh socle --portail`), `creche.sh creer` crée le code de
supervision et l'instance s'active seule une fois la crèche créée.

## Répondre aux alertes

| Alerte | Quoi faire |
|---|---|
| Serveur muet | `./creche.sh etat`, puis `docker logs creche-<crèche>` |
| Sauvegarde en retard | Lire l'erreur dans l'historique de la crèche ; vérifier l'espace disque |
| Espace disque | Agrandir le disque ; les archives les plus anciennes sont déjà tournées |
| Abonnement | Renouveler : `./creche.sh abonnement …` |
| Tablette muette | Appeler la crèche : tablette éteinte, Wi-Fi, ou tablette remplacée |
| Portail en échec | Vérifier le portail et son adresse |
