#!/bin/sh
# Prépare socle.env et la fiche du coffre (docs/EXPLOITATION.md § 2.6). En root, sur le serveur :
#   ./secrets.sh [--domaine ticagou.nc] [--fiche <fichier>|-]
# - crée socle.env depuis socle.env.example s'il manque (chmod 600) ;
# - génère chaque secret encore vide ; ne remplace JAMAIS un secret déjà renseigné (la base, les fichiers du portail
#   et les sauvegardes en dépendent) : on peut le relancer sans risque ;
# - écrit la fiche du coffre : socle.env et creche.env de chaque crèche (démonstrations exclues).
#   Relancez-le après chaque création de crèche pour une fiche à jour.
set -eu
cd "$(dirname "$0")"
umask 077

info() { echo "$@" >&2; }

# Fiche dans le dossier de l'utilisateur qui a fait « sudo » : il la récupère avec scp, sans droits root.
proprietaire=${SUDO_USER:-}
[ "$proprietaire" = root ] && proprietaire=""
if [ -n "$proprietaire" ]; then
    dossier_fiche=$(getent passwd "$proprietaire" | cut -d: -f6)
else
    dossier_fiche=$HOME
fi
fiche="$dossier_fiche/ticagou-coffre-$(date +%Y-%m-%d-%H%M).txt"
domaine=""
while [ $# -gt 0 ]; do
    case $1 in
        --domaine) domaine=${2:?--domaine <nom>}; shift ;;
        --fiche) fiche=${2:?--fiche <fichier> ou -}; shift ;;
        -h | --help | aide) sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) info "Option inconnue : $1"; exit 1 ;;
    esac
    shift
done

command -v openssl >/dev/null || { info "openssl manquant : apt -y install openssl"; exit 1; }

if [ ! -f socle.env ]; then
    cp socle.env.example socle.env
    info "socle.env créé depuis socle.env.example."
fi
chmod 600 socle.env
# Dernière ligne sans retour : un ajout se collerait à elle.
[ -z "$(tail -c 1 socle.env)" ] || echo >>socle.env

lire() { grep "^$1=" socle.env | tail -n 1 | cut -d= -f2-; }

# Remplace la ligne VAR=… (ou l'ajoute) ; la valeur passe par l'environnement, jamais par une expression sed.
ecrire() {
    if grep -q "^$1=" socle.env; then
        VALEUR=$2 awk -v cle="$1=" 'index($0, cle) == 1 { print cle ENVIRON["VALEUR"]; next } { print }' \
            socle.env >socle.env.tmp
        cat socle.env.tmp >socle.env
        rm -f socle.env.tmp
    else
        printf '%s=%s\n' "$1" "$2" >>socle.env
    fi
}

generes=""
# generer <VAR> base64|hex : seulement si la valeur est vide.
generer() {
    if [ -n "$(lire "$1")" ]; then return 0; fi
    case $2 in
        base64) valeur=$(openssl rand -base64 32 | tr -d '\n') ;;
        hex) valeur=$(openssl rand -hex 24) ;;
    esac
    ecrire "$1" "$valeur"
    generes="$generes $1"
}
defaut() { if [ -z "$(lire "$1")" ]; then ecrire "$1" "$2"; fi; }

[ -n "$domaine" ] && ecrire DOMAINE_BASE "$domaine"
base=$(lire DOMAINE_BASE)
[ -n "$base" ] || { info "Renseignez le domaine : ./secrets.sh --domaine ticagou.nc"; exit 1; }
defaut TICAGOU_DONNEES /opt/ticagou/donnees
defaut PORTAL_DOMAIN "parents.$base"
defaut CONSOLE_DOMAIN "console.$base"
# Console joignable depuis n'importe quel ordinateur, protégée par mot de passe et code (ADR 0017) ; « adresses » ou
# « tunnel » pour la fermer davantage. Une liste d'adresses déjà saisie est respectée : rien ne s'ouvre sans vous.
[ -n "$(lire CONSOLE_ADRESSES_AUTORISEES)" ] || defaut CONSOLE_ACCES internet

generer POSTGRES_PASSWORD base64
generer PORTAL_DB_PASSWORD base64
generer PORTAL_FILE_KEY base64
generer PORTAL_ADMIN_TOKEN base64
generer CONSOLE_DB_PASSWORD base64
generer CONSOLE_SECRET_KEY base64
generer CONSOLE_SETUP_TOKEN hex
generer CONSOLE_HOST_TOKEN hex

if [ -n "$generes" ]; then
    info "Secrets générés dans socle.env :$generes"
else
    info "Aucun secret à générer : socle.env est déjà complet (rien n'a été remplacé)."
fi

creches="$(lire TICAGOU_DONNEES)/creches"

ecrire_fiche() {
    cat <<FIN
Ti'Cagou — fiche du coffre
Serveur : $(hostname) ($(lire DOMAINE_BASE)) — fiche du $(date '+%d/%m/%Y à %H:%M')

Rangez chaque bloc dans une note sécurisée de votre coffre, puis effacez ce fichier (sur le serveur et sur le PC).

======================================================================
SOCLE — deploy/socle.env
======================================================================
Irremplaçables : sans eux, des données deviennent illisibles.
  POSTGRES_PASSWORD   base de toutes les crèches (à remettre tel quel après une perte du serveur, § 9)
  PORTAL_FILE_KEY     photos, documents et sauvegardes du portail parents
  CONSOLE_SECRET_KEY  sauvegardes et exports de la console, codes de double authentification
Les autres se régénèrent, mais gardez-les : la fiche permet de refaire socle.env à l'identique.

FIN
    grep -v '^[[:space:]]*#' socle.env | grep -v '^[^=]*=$' | grep -v '^[[:space:]]*$'
    echo
    echo "Premier compte de la console (sans effet dès qu'un compte existe) :"
    echo "  https://$(lire CONSOLE_DOMAIN)/premier-compte?jeton=$(lire CONSOLE_SETUP_TOKEN)"
    echo "  par tunnel : ssh -L 8095:127.0.0.1:8095 <serveur>, puis http://localhost:8095/premier-compte?jeton=$(lire CONSOLE_SETUP_TOKEN)"

    nombre=0
    for env in "$creches"/*/creche.env; do
        [ -f "$env" ] || continue
        grep -q '^DEMO=1$' "$env" && continue
        nombre=$((nombre + 1))
        cat <<FIN

======================================================================
CRÈCHE $(basename "$(dirname "$env")") — $env
======================================================================
Irremplaçables : TICAGOU_STORAGE_KEY (documents) et TICAGOU_BACKUP_RECOVERYKEY (sauvegardes).

FIN
        cat "$env"
    done
    if [ $nombre = 0 ]; then
        echo
        echo "(Aucune crèche pour l'instant : relancez ./secrets.sh après chaque « creche.sh creer ».)"
    fi

    cat <<FIN

======================================================================
À AJOUTER VOUS-MÊME AU COFFRE (rien de cela n'est sur le serveur)
======================================================================
- Accès au serveur : adresse IP, utilisateur, phrase de passe de votre clé SSH
- Clé de déploiement GitHub : /root/.ssh/ticagou_deploy (se refait sans perte)
- Stockage hors site : console du fournisseur, identifiant et clé secrète (rclone)
- Gestionnaire du domaine, service d'e-mails (SMTP, s'il n'est pas déjà ci-dessus)
FIN
}

if [ "$fiche" = - ]; then
    ecrire_fiche
    exit 0
fi
ecrire_fiche >"$fiche"
chmod 600 "$fiche"
[ -n "$proprietaire" ] && chown "$proprietaire" "$fiche"

nom=$(basename "$fiche")
info
info "Fiche du coffre : $fiche"
info
info "1. Sur votre PC (PowerShell), récupérez-la :"
info "     scp ${proprietaire:-root}@<adresse du serveur>:$fiche ."
info "2. Rangez son contenu dans votre coffre, puis effacez-la sur le PC :  Remove-Item $nom"
info "3. Et ici, sur le serveur :  shred -u $fiche"
