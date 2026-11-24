#!/usr/bin/env bash
# Zet de certificaten en sleutels van één peer als Secret `fsc-pki` in zijn namespace, in de vorm die de
# chart fsc-peer verwacht. De sleutels gaan van schijf rechtstreeks naar het cluster.
#
# Gebruik: deploy/fsc-testnet/pki-secret.sh <checkout van moza-fsc-testnet> <peer> <namespace>
#   bijvoorbeeld: ... ~/dev/fieldlab/moza-fsc-testnet nmc tnt-notificatiedienst--nmc
set -euo pipefail

PKI="$(cd "${1:?gebruik: $0 <checkout> <peer> <namespace>}/pki" && pwd)"
PEER="${2:?peer ontbreekt}"
NS="${3:?namespace ontbreekt}"

args=(--from-file="ca-root.pem=$PKI/ca/root.pem"
      --from-file="internal-root.pem=$PKI/internal/$PEER/ca/root.pem")
for dir in "$PKI/internal/$PEER"/*/; do
  e="$(basename "$dir")"
  [ "$e" = ca ] && continue
  args+=(--from-file="internal-$e-cert.pem=$dir/cert.pem" --from-file="internal-$e-key.pem=$dir/key.pem")
done
for dir in "$PKI/out/$PEER"/*/; do
  e="$(basename "$dir")"
  [ -s "$dir/cert.pem" ] || { echo "geen getekend group-cert voor $PEER/$e" >&2; exit 1; }
  args+=(--from-file="group-$e-cert.pem=$dir/cert.pem" --from-file="group-$e-key.pem=$dir/key.pem")
done

# Server-side apply: client-side zou het hele Secret, met de sleutels, in de annotatie
# last-applied-configuration zetten, en die toont `kubectl describe`.
kubectl -n "$NS" create secret generic fsc-pki "${args[@]}" --dry-run=client -o yaml \
  | kubectl -n "$NS" apply --server-side --force-conflicts -f -
kubectl -n "$NS" get secret fsc-pki -o go-template='{{range $k, $v := .data}}{{$k}}{{"\n"}}{{end}}'
