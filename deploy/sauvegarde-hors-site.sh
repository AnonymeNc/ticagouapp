#!/bin/sh
# Copie hors site, chaque matin après les sauvegardes de la nuit : les archives des crèches (déjà chiffrées par
# l'application) et un dump chiffré du portail, vers un autre hébergeur. rclone copy n'efface jamais : la rotation se
# règle chez le fournisseur, et la clé d'accès de ce serveur ne doit pas pouvoir supprimer.
# Installation (root) :  crontab -e  puis la ligne
#   30 6 * * * /opt/ticagou/ticagou-web/deploy/sauvegarde-hors-site.sh >> /var/log/ticagou-hors-site.log 2>&1
set -eu
cd "$(dirname "$0")"
set -a
# shellcheck disable=SC1091
. ./socle.env
set +a
: "${RCLONE_DESTINATION:?Renseignez RCLONE_DESTINATION dans socle.env}"

echec=0
for dossier in "$TICAGOU_DONNEES"/creches/*/; do
    [ -d "$dossier/data/backups" ] || continue
    slug=$(basename "$dossier")
    rclone copy "$dossier/data/backups" "$RCLONE_DESTINATION/creches/$slug" --include '*.tcb' || { echo "ÉCHEC : $slug"; echec=1; }
done

if docker compose -f socle.yml --env-file socle.env ps --status running portail 2>/dev/null | grep -q portail; then
    fichier="/tmp/portail-$(date +%Y-%m-%d).dump.enc"
    docker compose -f socle.yml --env-file socle.env exec -T db pg_dump -U ticagou_admin -Fc portail \
        | openssl enc -aes-256-cbc -pbkdf2 -salt -pass env:PORTAL_FILE_KEY -out "$fichier"
    rclone copy "$fichier" "$RCLONE_DESTINATION/portail" || echec=1
    rm -f "$fichier"
fi

echo "$(date '+%d/%m/%Y %H:%M') copie hors site : $([ $echec = 0 ] && echo réussie || echo 'EN ÉCHEC')"
exit $echec
