#!/usr/bin/env bash
# Maakt voor de FSC-peers van team Turing op Fundament (fieldlab, cluster C) de csr.json's, sleutels,
# een eigen internal-CA met internal-certs, en de CSR's voor de group-keten van het MOZa FSC-testnet.
# Volgt docs/peer-aansluiten.md, stap 3a en 3b, van MinBZK/moza-fsc-testnet.
#
# Gebruik: deploy/fsc-testnet/gen-csr.sh <checkout van moza-fsc-testnet>
#
# Bestaande sleutels blijven staan, zodat opnieuw draaien geen getekend cert ongeldig maakt.
# Privésleutels blijven in de checkout (gitignored) en horen niet in chat of git.
set -euo pipefail

TESTNET="${1:?gebruik: $0 <checkout van moza-fsc-testnet>}"
PKI="$(cd "$TESTNET/pki" && pwd)"
EXTERN_DOMEIN="cluster-c.fieldlab2026.nl"

# peer|OIN|namespace|prefix|endpoints|group-endpoints|extern bereikbare endpoints
PEERS=(
  "nmc|00000000000000000050|tnt-notificatiedienst--nmc|nmc|manager controller inway outway txlog|manager inway outway|manager inway"
  "profielservice-mock|00000000000000000060|tnt-notificatiedienst--mock-profielservice|ps|manager controller inway txlog|manager inway|manager inway"
)

declare -A KORT=([manager]=fscmgr [controller]=fscctl [inway]=fscinway [outway]=fscoutway [txlog]=fsctxlog)

command -v cfssl >/dev/null && command -v cfssljson >/dev/null && command -v jq >/dev/null \
  || { echo "cfssl, cfssljson en jq zijn nodig" >&2; exit 1; }

cd "$PKI"
for regel in "${PEERS[@]}"; do
  IFS='|' read -r peer oin ns prefix endpoints group extern <<<"$regel"

  for e in $endpoints; do
    svc="$prefix-${KORT[$e]}"
    hosts=()
    [[ " $extern " == *" $e "* ]] && hosts+=("$svc.$EXTERN_DOMEIN")
    hosts+=("$svc" "$svc.$ns" "$svc.$ns.svc" "$svc.$ns.svc.cluster.local")
    mkdir -p "peers/$peer/$e"
    jq -n --arg cn "${hosts[0]}" --arg sn "$oin" --arg o "$peer" \
      --argjson hosts "$(printf '%s\n' "${hosts[@]}" | jq -R . | jq -s .)" \
      '{CN:$cn, key:{algo:"rsa", size:4096}, hosts:$hosts, serialnumber:$sn, names:[{O:$o, C:"NL"}]}' \
      > "peers/$peer/$e/csr.json"
  done

  if [ ! -f "internal/$peer/ca/root-key.pem" ]; then
    mkdir -p "internal/$peer/ca"
    cfssl genkey -initca internal-ca.json 2>/dev/null | cfssljson -bare "internal/$peer/ca/root"
    rm "internal/$peer/ca/root.csr"
  fi

  for e in $endpoints; do
    [ -f "internal/$peer/$e/key.pem" ] && continue
    mkdir -p "internal/$peer/$e"
    cfssl gencert -config config.json -profile peer \
      -ca "internal/$peer/ca/root.pem" -ca-key "internal/$peer/ca/root-key.pem" \
      "peers/$peer/$e/csr.json" 2>/dev/null | cfssljson -bare "internal/$peer/$e/cert"
    mv "internal/$peer/$e/cert-key.pem" "internal/$peer/$e/key.pem"
    rm "internal/$peer/$e/cert.csr"
  done

  for e in $group; do
    [ -f "out/$peer/$e/key.pem" ] && continue
    mkdir -p "out/$peer/$e"
    cfssl genkey "peers/$peer/$e/csr.json" 2>/dev/null | cfssljson -bare "out/$peer/$e/cert"
    mv "out/$peer/$e/cert-key.pem" "out/$peer/$e/key.pem"
  done

  chmod 600 "internal/$peer"/*/*key.pem "out/$peer"/*/key.pem
  echo "== $peer ($oin): CSR's voor de beheerder:"
  for e in $group; do echo "   $PKI/out/$peer/$e/cert.csr"; done
done
