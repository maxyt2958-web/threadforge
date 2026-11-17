#!/usr/bin/env bash
# LAN + HTTPS origin whitelist for the dev Keycloak realm (Docker Compose variant).
#
# The repo's scripts/dev-lan-realm.sh hardcodes `podman`, which is not installed here.
# This is the same operation against `docker compose`. Re-run after every `docker compose up -d`
# that recreates Keycloak: it runs start-dev with an in-memory H2, so realm edits do not survive.
set -euo pipefail

IP="${1:-10.6.216.21}"
HTTPS_ORIGIN="https://${IP}:8443"
HTTP_ORIGIN="http://${IP}:8080"

KC=(docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh)
"${KC[@]}" config credentials --server http://localhost:8080 \
  --realm master --user admin --password admin >/dev/null

ID="$("${KC[@]}" get clients -r tforge-realm -q clientId=tforge-client \
      --fields id --format csv --noquotes 2>/dev/null | tr -d '\r\n')"
if [[ -z "$ID" ]]; then
  echo "client tforge-client not found in realm tforge-realm — is Keycloak done importing?" >&2
  exit 1
fi

REDIRECTS="[\"http://localhost:8080/*\",\"http://127.0.0.1:8080/*\",\"${HTTP_ORIGIN}/*\",\"${HTTPS_ORIGIN}/*\"]"
ORIGINS="[\"http://localhost:8080\",\"http://127.0.0.1:8080\",\"${HTTP_ORIGIN}\",\"${HTTPS_ORIGIN}\"]"

# post.logout.redirect.uris = "+" means "same as redirectUris", so sign-out cannot drift.
"${KC[@]}" update "clients/$ID" -r tforge-realm \
  -s "redirectUris=$REDIRECTS" -s "webOrigins=$ORIGINS" \
  -s 'attributes."post.logout.redirect.uris"=+' >/dev/null

echo "tforge-client now accepts: $HTTP_ORIGIN and $HTTPS_ORIGIN"
"${KC[@]}" get "clients/$ID" -r tforge-realm --fields redirectUris,webOrigins,attributes
