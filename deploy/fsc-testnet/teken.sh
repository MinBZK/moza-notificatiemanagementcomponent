#!/usr/bin/env bash
# Tekent de group-CSR's van de peers van team Turing met de test-CA van het MOZa FSC-testnet
# (docs/peer-aansluiten.md, stap 3c). Alleen voor wie de intermediate-sleutel mag ontsleutelen
# (pki/.sops.yaml). De sleutel staat alleen lokaal zolang dit script draait.
#
# Gebruik: deploy/fsc-testnet/teken.sh <checkout van moza-fsc-testnet>
set -euo pipefail

PKI="$(cd "${1:?gebruik: $0 <checkout van moza-fsc-testnet>}/pki" && pwd)"

# peer|OIN|group-endpoints, gelijk aan gen-csr.sh
PEERS=(
  "nmc|00000000000000000050|manager inway outway"
  "profielservice-mock|00000000000000000060|manager inway"
)

cd "$PKI"
trap './geheimen.sh wis groep' EXIT
# ophalen draait verify.sh, en dat is rood zolang onze group-sleutels nog geen cert hebben. Daarom
# telt hier alleen of de intermediate-sleutel er is; na het tekenen moet verify.sh wel groen zijn.
./geheimen.sh ophalen groep || true
[ -f ca/intermediate-key.pem ] || { echo "intermediate-sleutel niet opgehaald" >&2; exit 1; }

for regel in "${PEERS[@]}"; do
  IFS='|' read -r peer oin endpoints <<<"$regel"
  for e in $endpoints; do
    # Opnieuw tekenen geeft een nieuwe thumbprint, en daarmee breken de contracten van de peer (de
    # service connection noemt de thumbprint van de outway). Voor een nieuw cert: eerst cert.pem weg.
    if [ -s "out/$peer/$e/cert.pem" ]; then
      echo "al getekend: $peer/$e"
      continue
    fi
    csr="out/$peer/$e/cert.csr"
    subject="$(openssl req -in "$csr" -noout -verify -subject -nameopt sep_multiline)"
    # cfssl sign neemt bij twee serialNumbers de laatste; dan zou een peer zich als een ander voordoen.
    if [ "$(grep -c 'serialNumber=' <<<"$subject")" != 1 ] || ! grep -q "serialNumber=$oin\$" <<<"$subject"; then
      echo "NIET GETEKEND: $csr heeft niet precies één serialNumber=$oin" >&2
      exit 1
    fi
    cfssl sign -config config.json -profile peer \
      -ca ca/intermediate.pem -ca-key ca/intermediate-key.pem "$csr" | cfssljson -bare "out/$peer/$e/leaf"
    # Keten: leaf + intermediate. Zonder intermediate: "certificate signed by unknown authority".
    cat "out/$peer/$e/leaf.pem" ca/intermediate.pem > "out/$peer/$e/cert.pem"
    rm -f "out/$peer/$e/leaf.pem" "out/$peer/$e/leaf.csr"
    echo "getekend: $peer/$e"
  done
done

./verify.sh
