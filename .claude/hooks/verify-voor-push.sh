#!/usr/bin/env bash
# PreToolUse-hook voor Bash: draait ./mvnw verify vóór een git push en blokkeert de push als
# dat faalt. Verifieert in een tijdelijke worktree de bron van elke refspec, of HEAD als de push
# geen refspec noemt, en slaat een commit over waarop verify al slaagde. Bij --all of --mirror
# verifieert hij alleen HEAD. Vóór verify draait hij ./mvnw spotless:check, zodat een
# opmaakfout de push binnen seconden tegenhoudt in plaats van na de hele testsuite.
#
# De herkenning is een benadering: tekst tussen aanhalingstekens en de inhoud van een heredoc
# (zoals een commitmessage of PR-body) tellen niet mee. Een push die niet herkend wordt,
# bijvoorbeeld na een cd naar een pad met spaties, gaat zonder verify door; CI draait verify dan
# alsnog als verplichte check.
#
# Loopt de hook over zijn time-out uit settings.json, dan stopt Claude Code hem met SIGTERM,
# samen met Maven, en voert het commando toch uit. De EXIT-trap ruimt de worktree dan nog op.
set -uo pipefail

invoer=$(cat)

if ! command -v jq >/dev/null 2>&1; then
    # Zonder jq is het commando niet uit te lezen; meld het alleen als het om een push lijkt te gaan.
    if printf '%s' "$invoer" | grep -q 'git' && printf '%s' "$invoer" | grep -q 'push'; then
        printf '{"systemMessage": "verify-voor-push: jq ontbreekt, ./mvnw verify is niet gedraaid. CI draait hem wel."}\n'
    fi

    exit 0
fi

commando=$(printf '%s' "$invoer" | jq -r '.tool_input.command // empty')
sessiemap=$(printf '%s' "$invoer" | jq -r '.cwd // empty')

# Laat de inhoud van heredocs en tekst tussen aanhalingstekens weg, ook als die over meerdere
# regels loopt, en maak van elke regelovergang buiten aanhalingstekens een ';'. Een regel die op
# een backslash eindigt, loopt door op de volgende.
kaal=$(printf '%s\n' "$commando" | awk '
    BEGIN { q = ""; einde = ""; uit = "" }
    {
        regel = $0

        if (einde != "") {
            r = regel
            gsub(/^[ \t]+|[ \t]+$/, "", r)

            if (r == einde) einde = ""
            next
        }

        if (match(regel, /(^|[^<])<<-?[ \t]*["\047]?[A-Za-z_][A-Za-z0-9_]*/)) {
            einde = substr(regel, RSTART, RLENGTH)
            sub(/^[^<]*<<-?[ \t]*["\047]?/, "", einde)
        }

        n = length(regel)
        doorloop = 0

        for (i = 1; i <= n; i++) {
            c = substr(regel, i, 1)

            if (q == "\047") { if (c == "\047") q = ""; continue }
            if (q == "\"") {
                if (c == "\\") { i++; continue }
                if (c == "\"") q = ""
                continue
            }
            if (c == "\\") {
                if (i == n) doorloop = 1
                i++
                continue
            }
            if (c == "\047" || c == "\"") { q = c; continue }
            uit = uit c
        }

        if (q == "") uit = uit (doorloop ? " " : ";")
    }
    END { print uit }')

git_aanroep='(^|[;&|([:space:]])([^;&|[:space:]]*/)?git([[:space:]]+(-[Cc][[:space:]]+[^;&|[:space:]]+|--?[^;&|[:space:]]+))*[[:space:]]+'

if ! printf '%s' "$kaal" | grep -qE "${git_aanroep}push([[:space:]]|\)|;|&|\||$)"; then
    exit 0
fi

# Een dry-run of het verwijderen van een remote branch pusht geen code.
push_argumenten=$(printf '%s' "$kaal" | sed -nE 's/.*git[^;&|]*[[:space:]]push([^;&|)]*).*/\1/p')

if printf '%s' "$push_argumenten" | grep -qE '(^|[[:space:]])(--dry-run|-n|--delete|-d|:[^[:space:]]+)([[:space:]]|$)'; then
    exit 0
fi

# Bepaal de map: git -C <map>, anders de laatste cd vóór de push, anders de sessiemap.
basis="${sessiemap:-${CLAUDE_PROJECT_DIR:-$(pwd)}}"
map=$(printf '%s' "$kaal" | sed -nE 's/.*git[[:space:]]+-C[[:space:]]+([^;&|[:space:]]+)[^;&|]*[[:space:]]push.*/\1/p')

if [ -z "$map" ]; then
    voor_push=$(printf '%s' "$kaal" | sed -E 's/git[^;&|]*[[:space:]]push.*//')
    map=$(printf '%s' "$voor_push" | grep -oE '(^|[;&|([:space:]])cd[[:space:]]+[^;&|[:space:]]+' | tail -n 1 | sed -E 's/.*cd[[:space:]]+//')
fi

case "$map" in
    "") map="$basis" ;;
    /*) ;;
    "~"*) map="$HOME${map#\~}" ;;
    *) map="$basis/$map" ;;
esac

root=$(git -C "$map" rev-parse --show-toplevel 2>/dev/null) || exit 0
[ -x "$root/mvnw" ] || exit 0

# De hook draait vóór het commando: commits die hetzelfde commando maakt, binnenhaalt of
# uitcheckt, zouden ongeverifieerd mee gaan. Dat geldt ook bij een schone werkboom.
if printf '%s' "$kaal" | grep -qE "${git_aanroep}(commit|merge|rebase|cherry-pick|am|revert|pull|checkout|switch|reset)([[:space:]]|;|&|\||\)|$)"; then
    echo "Dit commando maakt, haalt of wisselt commits (commit, merge, rebase, pull, checkout, reset, ...) én pusht. De hook draait vóór het commando, dus hij zou de verkeerde commit verifiëren. Doe dat eerst los, en push daarna." >&2
    exit 2
fi

# Bepaal wat er gepusht wordt: de bron van elke refspec na de remote, anders HEAD. Een bron die
# niet bestaat, laat git push zelf falen.
commits=""
melding=""
positie=0
volgende_is_waarde=0
set -f

for argument in $push_argumenten; do
    if [ "$volgende_is_waarde" = 1 ]; then
        volgende_is_waarde=0
        continue
    fi

    case "$argument" in
        --repo) positie=1; volgende_is_waarde=1; continue ;;
        --repo=*) positie=1; continue ;;
        -o|--push-option|--receive-pack|--exec) volgende_is_waarde=1; continue ;;
        --all|--mirror|--branches)
            melding="verify-voor-push: bij $argument is alleen HEAD geverifieerd."
            continue ;;
        -*) continue ;;
    esac

    positie=$((positie + 1))
    [ "$positie" = 1 ] && continue

    bron=${argument#+}
    bron=${bron%%:*}
    [ -n "$bron" ] || continue

    if sha=$(git -C "$root" rev-parse --verify --quiet "$bron^{commit}"); then
        commits="$commits $sha"
    fi
done

set +f
[ -n "$commits" ] || commits=$(git -C "$root" rev-parse HEAD)

gemeenschappelijk=$(cd "$root" && cd "$(git rev-parse --git-common-dir)" && pwd)
marker="$gemeenschappelijk/nmc-verify-geslaagd"
te_verifieren=""

for sha in $(printf '%s\n' $commits | sort -u); do
    if [ -f "$marker" ] && grep -qxF "$sha" "$marker"; then
        continue
    fi

    te_verifieren="$te_verifieren $sha"
done

meld() {
    if [ -n "$melding" ]; then
        printf '{"systemMessage": "%s"}\n' "$melding"
    fi
}

if [ -z "$te_verifieren" ]; then
    meld
    exit 0
fi

if [ -f "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
    set +u
    # shellcheck disable=SC1091
    source "$HOME/.sdkman/bin/sdkman-init.sh"
    set -u
fi

if ! java -version >/dev/null 2>&1; then
    printf '{"systemMessage": "verify-voor-push: geen Java gevonden, ./mvnw verify is niet gedraaid. CI draait hem wel."}\n'
    exit 0
fi

if ! tijdelijk=$(mktemp -d 2>/dev/null); then
    printf '{"systemMessage": "verify-voor-push: kon geen tijdelijke map maken, ./mvnw verify is niet gedraaid. CI draait hem wel."}\n'
    exit 0
fi

log="$tijdelijk/verify.log"

# Dubbel --force, omdat een worktree vergrendeld kan zijn (sommige tools doen dat bij aanmaken).
ruim_op() {
    for werkmap in "$tijdelijk"/wt-*; do
        [ -d "$werkmap" ] && git -C "$root" worktree remove --force --force "$werkmap" >/dev/null 2>&1
    done

    rm -rf "$tijdelijk"
}
trap ruim_op EXIT

for sha in $te_verifieren; do
    werkmap="$tijdelijk/wt-$sha"

    if ! git -C "$root" worktree add --detach --quiet "$werkmap" "$sha" >/dev/null 2>&1; then
        printf '{"systemMessage": "verify-voor-push: kon geen tijdelijke worktree maken, ./mvnw verify is niet gedraaid. CI draait hem wel."}\n'
        exit 0
    fi

    # Eerst de opmaak: die controle duurt seconden, verify minuten. Een commit van vóór Spotless
    # slaat hem over, anders blokkeert de onbekende plugin de push.
    if grep -q "spotless-maven-plugin" "$werkmap/pom.xml" 2>/dev/null \
        && ! (cd "$werkmap" && ./mvnw -B -q spotless:check) >"$log" 2>&1; then
        {
            echo "De opmaak klopt niet op $(git -C "$root" rev-parse --short "$sha") in $root; de push is tegengehouden. Draai ./mvnw spotless:apply en commit het resultaat. Laatste regels:"
            tail -n 30 "$log"
        } >&2
        exit 2
    fi

    if ! (cd "$werkmap" && ./mvnw -B -q verify) >"$log" 2>&1; then
        {
            echo "./mvnw verify faalt op $(git -C "$root" rev-parse --short "$sha") in $root; de push is tegengehouden. Laatste regels:"
            tail -n 60 "$log"
        } >&2
        exit 2
    fi

    echo "$sha" >>"$marker"
done

meld
exit 0
