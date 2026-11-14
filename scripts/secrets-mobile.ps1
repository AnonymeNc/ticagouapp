<#
.SYNOPSIS
  Génère tout ce qui peut l'être pour publier les applications mobiles et le range dans les secrets GitHub.

.DESCRIPTION
  1. Clé de signature Android (« clé d'importation » de Google Play) : fichier .jks, mot de passe et alias tirés au
     hasard, empreintes SHA-1 et SHA-256. Créée une seule fois : le script refuse d'en créer une deuxième.
  2. Secrets GitHub du dépôt : ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS,
     ANDROID_KEY_PASSWORD ; puis, si vous donnez les fichiers obtenus chez Apple et Firebase, APPLE_TEAM_ID,
     APP_STORE_CONNECT_KEY_ID, APP_STORE_CONNECT_ISSUER_ID, APP_STORE_CONNECT_KEY_BASE64 et GOOGLE_SERVICES_<APP>.
  3. Une fiche texte dans le dossier du coffre, avec tout ce qui a été généré et ce qu'il reste à faire.

  Relançable sans risque : rien n'est jamais remplacé. Guide pas à pas : docs/PUBLIER-LES-APPLICATIONS.md.

.PARAMETER Coffre
  Dossier où ranger la clé et la fiche (défaut : Documents\Ticagou-coffre). À sauvegarder hors de ce PC.

.PARAMETER Societe
  Nom de la société éditrice, inscrit dans le certificat de la clé Android (défaut : Ti'Cagou).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\secrets-mobile.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\secrets-mobile.ps1 -AppleTeamId ABCDE12345 -AppStoreKeyFile C:\Users\moi\Downloads\AuthKey_XYZ.p8 -AppStoreKeyId XYZ -AppStoreIssuerId 69a6de7e-...

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\secrets-mobile.ps1 -GoogleServicesDirection C:\...\direction\google-services.json -GoogleServicesPointage C:\...\pointage\google-services.json -GoogleServicesParents C:\...\parents\google-services.json
#>
param(
    [string]$Coffre = (Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'Ticagou-coffre'),
    [string]$Societe = "Ti'Cagou",
    [string]$AppleTeamId,
    [string]$AppStoreKeyId,
    [string]$AppStoreIssuerId,
    [string]$AppStoreKeyFile,
    [string]$GoogleServicesDirection,
    [string]$GoogleServicesPointage,
    [string]$GoogleServicesParents
)

$ErrorActionPreference = 'Stop'
$repo = 'AnonymeNc/ticagouapp'
$lines = New-Object System.Collections.Generic.List[string]
function Note([string]$text) { $script:lines.Add($text); Write-Host $text }
$fiche = $null
# La fiche est écrite au fur et à mesure : un mot de passe généré n'est jamais perdu, même si la suite échoue.
function Save-Fiche { $script:lines | Set-Content -Path $script:fiche -Encoding UTF8 }

function New-Password([int]$length = 32) {
    # Lettres et chiffres seulement : aucun caractère à échapper dans Gradle, GitHub ou une ligne de commande.
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'.ToCharArray()
    $bytes = New-Object byte[] $length
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
}

# keytool écrit sur stderr : sous PowerShell 5.1 avec Stop, ce serait une erreur bloquante.
function Invoke-Keytool {
    $ErrorActionPreference = 'Continue'
    $output = & $script:keytool.Source @args 2>&1 | ForEach-Object { "$_" }
    return ($output -join "`n")
}

function Set-RepoSecret([string]$name, [string]$value) {
    gh secret set $name --repo $repo --body $value | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Secret $name non enregistré (gh auth status ?)." }
    Note "  secret GitHub $name : enregistré"
}

# --- Vérifications ---------------------------------------------------------------------------------
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw 'GitHub CLI (gh) manquant : https://cli.github.com, puis « gh auth login ».' }
gh auth status *> $null
if ($LASTEXITCODE -ne 0) { throw 'GitHub CLI non connecté : lancez « gh auth login ».' }
$keytool = Get-Command keytool -ErrorAction SilentlyContinue
if (-not $keytool -and $env:JAVA_HOME) { $keytool = Get-Command (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -ErrorAction SilentlyContinue }
if (-not $keytool -and (Get-Command java -ErrorAction SilentlyContinue)) {
    # java peut n'être qu'un raccourci d'Oracle (javapath) : on demande à Java où il est installé.
    $javaHome = (cmd /c 'java -XshowSettings:properties -version 2>&1' | Select-String 'java.home = (.+)$').Matches.Groups[1].Value.Trim()
    if ($javaHome) { $keytool = Get-Command (Join-Path $javaHome 'bin\keytool.exe') -ErrorAction SilentlyContinue }
}
if (-not $keytool) { throw 'keytool introuvable : installez le JDK 21 (il le fournit).' }
$existing = @(gh secret list --repo $repo --json name --jq '.[].name')
New-Item -ItemType Directory -Force $Coffre | Out-Null
$fiche = Join-Path $Coffre "fiche-secrets-mobile-$(Get-Date -Format 'yyyy-MM-dd-HHmm').txt"

Note "Ti'Cagou - secrets des applications mobiles ($(Get-Date -Format 'dd/MM/yyyy HH:mm'))"
Note "Coffre : $Coffre"
Note ''

# --- 1. Clé Android --------------------------------------------------------------------------------
Note '== Clé de signature Android (clé d''importation Google Play) =='
$jks = Join-Path $Coffre 'ticagou-android-upload.jks'
$alias = 'ticagou-upload'
if (Test-Path $jks) {
    Note "  Déjà créée : $jks (mot de passe dans la fiche précédente du coffre). Rien n'est changé."
} elseif ($existing -contains 'ANDROID_KEYSTORE_BASE64') {
    Note '  Une clé est déjà enregistrée sur GitHub mais absente de ce coffre : rien n''est changé.'
    Note '  Remettez le fichier .jks d''origine dans le coffre. Ne créez pas de nouvelle clé : Google Play refuserait les mises à jour.'
} else {
    # PKCS12 : un seul mot de passe pour le fichier et la clé.
    $password = New-Password
    $dname = "CN=$($Societe -replace ',', ' '), O=$($Societe -replace ',', ' '), L=Noumea, C=NC"
    $null = Invoke-Keytool -genkeypair -keystore $jks -storetype PKCS12 -alias $alias -keyalg RSA -keysize 4096 `
        -validity 10000 -storepass $password -keypass $password -dname $dname
    if (-not (Test-Path $jks)) { throw 'keytool n''a pas pu créer la clé.' }
    $listing = Invoke-Keytool -list -v -keystore $jks -storepass $password -alias $alias
    $sha1 = ([regex]'SHA1:\s*([0-9A-F:]+)').Match($listing).Groups[1].Value
    $sha256 = ([regex]'SHA256:\s*([0-9A-F:]+)').Match($listing).Groups[1].Value
    Note "  Fichier      : $jks"
    Note "  Alias        : $alias"
    Note "  Mot de passe : $password   (fichier et clé)"
    Note "  SHA-1        : $sha1"
    Note "  SHA-256      : $sha256"
    Note '  >>> Copiez ce fichier .jks et ce mot de passe dans votre gestionnaire de mots de passe ET sur une clé USB.'
    Save-Fiche
    Set-RepoSecret 'ANDROID_KEYSTORE_BASE64' ([Convert]::ToBase64String([IO.File]::ReadAllBytes($jks)))
    Set-RepoSecret 'ANDROID_KEYSTORE_PASSWORD' $password
    Set-RepoSecret 'ANDROID_KEY_PASSWORD' $password
    Set-RepoSecret 'ANDROID_KEY_ALIAS' $alias
}
Note ''

# --- 2. Apple (fichiers obtenus sur developer.apple.com et App Store Connect) ------------------------
Note '== Apple (App Store, TestFlight) =='
if ($AppleTeamId -and $AppStoreKeyId -and $AppStoreIssuerId -and $AppStoreKeyFile) {
    if (-not (Test-Path $AppStoreKeyFile)) { throw "Fichier introuvable : $AppStoreKeyFile" }
    Copy-Item $AppStoreKeyFile (Join-Path $Coffre (Split-Path $AppStoreKeyFile -Leaf)) -Force
    Set-RepoSecret 'APPLE_TEAM_ID' $AppleTeamId
    Set-RepoSecret 'APP_STORE_CONNECT_KEY_ID' $AppStoreKeyId
    Set-RepoSecret 'APP_STORE_CONNECT_ISSUER_ID' $AppStoreIssuerId
    Set-RepoSecret 'APP_STORE_CONNECT_KEY_BASE64' ([Convert]::ToBase64String([IO.File]::ReadAllBytes($AppStoreKeyFile)))
} elseif ($existing -contains 'APP_STORE_CONNECT_KEY_BASE64') {
    Note '  Déjà enregistrés sur GitHub.'
} else {
    Note '  À faire après l''ouverture du compte Apple (guide, étape C) : relancez avec -AppleTeamId, -AppStoreKeyId,'
    Note '  -AppStoreIssuerId et -AppStoreKeyFile.'
}
Note ''

# --- 3. Firebase (notifications Android) ------------------------------------------------------------
Note '== Firebase (notifications Android) =='
$firebase = @{ DIRECTION = $GoogleServicesDirection; POINTAGE = $GoogleServicesPointage; PARENTS = $GoogleServicesParents }
foreach ($app in 'DIRECTION', 'POINTAGE', 'PARENTS') {
    $file = $firebase[$app]
    if ($file) {
        if (-not (Test-Path $file)) { throw "Fichier introuvable : $file" }
        Copy-Item $file (Join-Path $Coffre "google-services-$($app.ToLower()).json") -Force
        Set-RepoSecret "GOOGLE_SERVICES_$app" ([Convert]::ToBase64String([IO.File]::ReadAllBytes($file)))
    } elseif ($existing -contains "GOOGLE_SERVICES_$app") {
        Note "  GOOGLE_SERVICES_$app : déjà enregistré sur GitHub."
    } else {
        Note "  GOOGLE_SERVICES_$app : à faire après la création du projet Firebase (guide, étape D)."
    }
}
Note ''
Note 'Côté serveur (APNS_*, FCM_SERVICE_ACCOUNT dans socle.env) : guide, étape E.'
Note 'Guide complet : docs/PUBLIER-LES-APPLICATIONS.md'

Save-Fiche
Write-Host ''
Write-Host "Fiche écrite : $fiche" -ForegroundColor Green
