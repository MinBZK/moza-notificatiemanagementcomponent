#!/usr/bin/env bash
# PreToolUse-hook voor Bash: draait ./mvnw verify vóór een git push en blokkeert de push als
# dat faalt. Verifieert de repo waar de push vandaan komt (ook een worktree), en slaat over
# als verify al slaagde op dezelfde commit.
set -uo pipefail

if ! command -v jq >/dev/null 2>&1; then
    printf '{"systemMessage": "verify-voor-push: jq ontbreekt, ./mvnw verify is niet gedraaid. CI draait hem wel."}\n'
    exit 0
fi

invoer=$(cat)
commando=$(printf '%s' "$invoer" | jq -r '.tool_input.command // empty')
sessiemap=$(printf '%s' "$invoer" | jq -r '.cwd // empty')

# git push, ook als git -C <map> push of na ; && | (
if ! printf '%s' "$commando" | grep -qE '(^|[;&|([:space:]])git([[:space:]]+-C[[:space:]]+[^[:space:]]+)?[[:space:]]+push([[:space:]]|\)|$)'; then
    exit 0
fi

if printf '%s' "$commando" | grep -qE '[[:space:]](--dry-run|-n)([[:space:]]|$)'; then
    exit 0
fi

# Bepaal de map: git -C <map>, anders de laatste cd vóór de push, anders de sessiemap.
basis="${sessiemap:-${CLAUDE_PROJECT_DIR:-$(pwd)}}"
map=$(printf '%s' "$commando" | sed -nE 's/.*git[[:space:]]+-C[[:space:]]+([^[:space:]]+)[[:space:]]+push.*/\1/p')

if [ -z "$map" ]; then
    voor_push=$(printf '%s' "$commando" | sed -E 's/git([[:space:]]+-C[[:space:]]+[^[:space:]]+)?[[:space:]]+push.*//')
    map=$(printf '%s' "$voor_push" | grep -oE 'cd[[:space:]]+[^;&|[:space:]]+' | tail -n 1 | sed -E 's/^cd[[:space:]]+//')
fi

map="${map%\"}"
map="${map#\"}"

case "$map" in
    "") map="$basis" ;;
    /*) ;;
    "~"*) map="$HOME${map#\~}" ;;
    *) map="$basis/$map" ;;
esac

root=$(git -C "$map" rev-parse --show-toplevel 2>/dev/null) || exit 0
cd "$root" || exit 0
[ -x ./mvnw ] || exit 0

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    echo "De werkboom in $root heeft niet-gecommitte wijzigingen. verify zou die meetesten terwijl de push alleen de commits stuurt. Commit of stash eerst, en push dan opnieuw." >&2
    exit 2
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

marker="target/.verify-geslaagd"
head=$(git rev-parse HEAD)

if [ -f "$marker" ] && [ "$(cat "$marker")" = "$head" ]; then
    exit 0
fi

log=$(mktemp)

if ./mvnw -B -q verify >"$log" 2>&1; then
    mkdir -p target
    echo "$head" >"$marker"
    rm -f "$log"
    exit 0
fi

{
    echo "./mvnw verify faalt in $root; de push is tegengehouden. Laatste regels:"
    tail -n 60 "$log"
} >&2
rm -f "$log"
exit 2
