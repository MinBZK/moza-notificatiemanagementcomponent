#!/usr/bin/env bash
# PreToolUse-hook voor Edit en Write: weigert wijzigingen aan gegenereerde code en aan
# migraties die al op een remote branch staan (die kunnen op een PR-preview of verder gedraaid
# hebben), en vraagt om bevestiging bij een migratie die alleen lokaal gecommit is.
# Werkt ook in een worktree naast de repo. Wijzigingen via Bash (sed -i) ziet deze hook niet.
set -uo pipefail

if ! command -v jq >/dev/null 2>&1; then
    printf '{"systemMessage": "bewaak-bestanden: jq ontbreekt, migraties en gegenereerde code zijn niet bewaakt."}\n'
    exit 0
fi

pad=$(jq -r '.tool_input.file_path // empty')
[ -z "$pad" ] && exit 0

case "$pad" in
    */target/generated-sources/*)
        echo "Gegenereerde code wordt bij elke build overschreven. Pas het contract of de generatorconfiguratie aan en bouw opnieuw." >&2
        exit 2
        ;;
    */src/main/resources/db/migration/V*.sql) ;;
    *) exit 0 ;;
esac

map=$(dirname "$pad")
root=$(git -C "$map" rev-parse --show-toplevel 2>/dev/null) || exit 0
relatief="${pad#"$root"/}"

if [ -n "$(git -C "$root" log --remotes -1 --format=%H -- "$relatief" 2>/dev/null)" ]; then
    echo "$relatief staat al op een remote branch en kan op een database gedraaid hebben. Flyway weigert een gewijzigd script (checksum). Voeg een nieuwe migratie toe." >&2
    exit 2
fi

if git -C "$root" ls-files --error-unmatch "$relatief" >/dev/null 2>&1; then
    jq -n --arg reden "$relatief is lokaal gecommit maar nog niet gepusht. Alleen wijzigen als deze migratie nog op geen enkele database is toegepast." \
        '{hookSpecificOutput: {hookEventName: "PreToolUse", permissionDecision: "ask", permissionDecisionReason: $reden}}'
fi

exit 0
