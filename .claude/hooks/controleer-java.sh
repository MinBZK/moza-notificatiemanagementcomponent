#!/usr/bin/env bash
# PostToolUse-hook voor Edit en Write: waarschuwt bij patronen die in review steeds terugkomen.
# Exit 2 toont de melding aan Claude; de wijziging zelf is dan al gedaan.
set -uo pipefail

if ! command -v jq >/dev/null 2>&1; then
    exit 0
fi

pad=$(jq -r '.tool_input.file_path // empty')

case "$pad" in
    */src/main/java/*.java) ;;
    *) exit 0 ;;
esac

[ -f "$pad" ] || exit 0
meldingen=""

# Tijd uit de systeemklok maakt code onvoorspelbaar in tests; injecteer de Clock.
klok=$(grep -nE '(Instant|OffsetDateTime|LocalDateTime|LocalDate|LocalTime|ZonedDateTime)\.now\(' "$pad" | grep -vi 'clock')

if [ -n "$klok" ]; then
    meldingen+="now() zonder Clock in $pad (gebruik de geïnjecteerde Clock):"$'\n'"$klok"$'\n'
fi

# Een JAX-RS-annotatie op een controllermethode laat alle annotaties van de gegenereerde interface vervallen.
case "$pad" in
    *Controller.java)
        jaxrs=$(grep -nE '^[[:space:]]*@(GET|POST|PUT|DELETE|PATCH|Path|Consumes|Produces|Valid)([^[:alnum:]_]|$)' "$pad")

        if [ -n "$jaxrs" ]; then
            meldingen+="JAX-RS-annotatie in $pad; die horen alleen op de gegenereerde interface:"$'\n'"$jaxrs"$'\n'
        fi
        ;;
esac

if [ -n "$meldingen" ]; then
    printf '%s' "$meldingen" >&2
    exit 2
fi

exit 0
