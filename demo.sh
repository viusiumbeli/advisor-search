#!/usr/bin/env bash
# Walks through what the API does, against a running instance.
#
#   ./demo.sh                        # against http://localhost:8080
#   BASE_URL=https://... API_KEY=... ./demo.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:-}"

# Only the deployed instance needs a key; locally this array stays empty. It is expanded as
# ${auth[@]+"${auth[@]}"} everywhere below, because an empty array under `set -u` is an error in the
# bash 3.2 that ships with macOS.
auth=()
if [[ -n "$API_KEY" ]]; then
  auth=(-H "X-API-Key: $API_KEY")
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "This demo formats its output with jq, which is not installed. Install it, or read the raw" >&2
  echo "JSON with: curl -s '$BASE_URL/search?q=address+proof'" >&2
  exit 1
fi

pretty() { jq "$@"; }

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
run() { printf '  $ %s\n' "$*"; }

if ! curl -sf ${auth[@]+"${auth[@]}"} "$BASE_URL/actuator/health" >/dev/null; then
  echo "No healthy instance at $BASE_URL. Start one with: docker compose up" >&2
  exit 1
fi

say "1. The task's client example: a fragment of an email domain finds the client"
run "GET /search?q=AldgateWealth"
curl -s ${auth[@]+"${auth[@]}"} -G "$BASE_URL/search" --data-urlencode "q=AldgateWealth" --data-urlencode "limit=1" \
  | pretty '.[0] | {type, score, matched_on, name: (.client.first_name + " " + .client.last_name), email: .client.email}'

say "2. The task's document example: every document that can evidence an address comes back,"
say "   including the electricity bill, which contains neither word and is reached by meaning alone"
run "GET /search?q=address%20proof"
curl -s ${auth[@]+"${auth[@]}"} -G "$BASE_URL/search" --data-urlencode "q=address proof" --data-urlencode "limit=4" \
  | pretty '[.[] | select(.type == "document") | {score, matched_on, title: .document.title}]'

say "3. A rare exact token: embeddings treat it as noise, full text search does not"
run "GET /search?q=PLC-88213"
curl -s ${auth[@]+"${auth[@]}"} -G "$BASE_URL/search" --data-urlencode "q=PLC-88213" --data-urlencode "limit=2" \
  | pretty '[.[] | select(.type == "document") | {score, matched_on, title: .document.title}]'

say "4. A misspelled surname still finds the client"
run "GET /search?q=Delacroix-Whitfeld"
curl -s ${auth[@]+"${auth[@]}"} -G "$BASE_URL/search" --data-urlencode "q=Delacroix-Whitfeld" --data-urlencode "limit=1" \
  | pretty '.[0] | {score, matched_on, email: .client.email}'

say "5. A whole question, answered from a document that shares no vocabulary with it"
run "GET /search?q=who%20can%20act%20for%20a%20client%20if%20they%20lose%20capacity"
curl -s ${auth[@]+"${auth[@]}"} -G "$BASE_URL/search" \
  --data-urlencode "q=who can act for a client if they lose capacity" --data-urlencode "limit=1" \
  | pretty '.[0] | {score, matched_on, title: .document.title, snippet: .snippet[0:120]}'

say "6. Creating a client, then a document, then finding it immediately"
stamp=$(date +%H%M%S)
email="demo.$(date +%s)@example.com"
run "POST /clients"
client_id=$(curl -s ${auth[@]+"${auth[@]}"} -X POST "$BASE_URL/clients" -H 'Content-Type: application/json' \
  -d "{\"first_name\":\"Demo\",\"last_name\":\"Client\",\"email\":\"$email\"}" \
  | jq -r .id)
echo "  created client $client_id"

run "POST /clients/$client_id/documents"
curl -s ${auth[@]+"${auth[@]}"} -X POST "$BASE_URL/clients/$client_id/documents" -H 'Content-Type: application/json' \
  -d '{"title":"Kitchen Extension Quotation '"$stamp"'","content":"Quotation for a single storey rear extension at 4 Alder Close. The builder estimates eleven weeks of work and requires a thirty per cent deposit before starting."}' \
  | pretty '{id, title, created_at}'

run "GET /search?q=building%20work%20estimate"
curl -s ${auth[@]+"${auth[@]}"} -G "$BASE_URL/search" --data-urlencode "q=building work estimate" --data-urlencode "limit=2" \
  | pretty '[.[] | select(.type == "document") | {score, matched_on, title: .document.title}]'

say "7. Extractive summary of a long document"
doc_id=$(curl -s ${auth[@]+"${auth[@]}"} -G "$BASE_URL/search" --data-urlencode "q=trustee duties" --data-urlencode "limit=5" \
  | jq -r 'map(select(.type == "document")) | .[0].document.id')
run "GET /documents/$doc_id/summary"
curl -s ${auth[@]+"${auth[@]}"} "$BASE_URL/documents/$doc_id/summary" \
  | pretty '{title, chunk_count, passages: [.passages[] | {chunk_index, text: .text[0:100]}]}'

say "8. A query with no plausible answer returns an empty array, not a page of noise"
run "GET /search?q=photosynthesis%20in%20tropical%20rainforest%20canopies"
curl -s ${auth[@]+"${auth[@]}"} -G "$BASE_URL/search" --data-urlencode "q=photosynthesis in tropical rainforest canopies" | pretty '.'

printf '\nAPI documentation: %s/swagger-ui.html\n' "$BASE_URL"
