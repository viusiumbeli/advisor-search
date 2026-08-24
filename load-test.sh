#!/usr/bin/env bash
# Load-tests a running instance and prints the markdown rows that docs/load-and-limits.md is built
# from: search latency across corpus scales and concurrency levels, ingest under load, and a mixed
# workload.
#
# This script WRITES documents and multiplies the corpus in place, so run it against a
# disposable stack, never one whose data you keep:
#
#   docker compose -p bench up -d
#   ./load-test.sh bench
#   docker compose -p bench down -v
#
# The first argument is the compose project name (default: bench), used to reach psql inside the
# db container for the corpus-growth stages. BASE_URL overrides the API address. Latencies are
# per-request curl over localhost without connection reuse, so they include connection setup and
# are conservative.
set -euo pipefail

PROJECT="${1:-bench}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
REQUESTS=200

for dep in curl jq python3 docker; do
  command -v "$dep" >/dev/null 2>&1 || { echo "$dep is required" >&2; exit 1; }
done

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
SEARCH_ROWS="$TMP/search_rows.md"
INGEST_ROWS="$TMP/ingest_rows.md"
: > "$SEARCH_ROWS"
: > "$INGEST_ROWS"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
now() { python3 -c 'import time; print(f"{time.time():.3f}")'; }

psql_db() {
  docker compose -p "$PROJECT" exec -T db psql -v ON_ERROR_STOP=1 -q -U advisor -d advisor_search "$@"
}

# Nearest-rank percentiles over a file of per-request seconds, plus throughput from wall time.
stats() { # <times-file> <wall-seconds>
  python3 - "$1" "$2" <<'PY'
import math, sys
ts = sorted(float(x) for x in open(sys.argv[1]))
wall, n = float(sys.argv[2]), len(ts)
pct = lambda p: ts[max(0, math.ceil(p * n) - 1)]
ms = lambda s: f"{s * 1000:.0f} ms"
print(f"{ms(pct(0.50))} | {ms(pct(0.95))} | {ms(pct(0.99))} | {ms(ts[-1])} | {n / wall:.1f} req/s")
PY
}

corpus_label() {
  psql_db -tAc "SELECT (SELECT count(*) FROM documents) || ' docs / '
                    || (SELECT count(*) FROM document_chunks) || ' chunks / '
                    || (SELECT count(*) FROM clients) || ' clients'" | tr -d '\r'
}

# Growth leaves cold, hint-bit-less pages and stale planner statistics behind; measuring straight
# after it would time page rewrites, not search. Vacuum, analyze, then warm the cache.
settle() {
  psql_db -c "VACUUM ANALYZE clients, documents, document_chunks;" >/dev/null
  for i in $(seq 1 20); do curl -s -o /dev/null "$BASE_URL/search?q=settle+$i"; done
}

search_cell() { # <concurrency>
  local c="$1" f="$TMP/times.$1" t0 t1 wall label
  label=$(corpus_label)
  t0=$(now)
  seq 1 "$REQUESTS" | xargs -P "$c" -I{} curl -s -o /dev/null -w '%{time_total}\n' \
    "$BASE_URL/search?q=quarterly+portfolio+rebalancing+{}" > "$f"
  t1=$(now)
  wall=$(python3 -c "print($t1 - $t0)")
  printf '| %s | %s | %s\n' "$label" "$c" "$(stats "$f" "$wall") |" >> "$SEARCH_ROWS"
  printf '  concurrency %-2s -> %s\n' "$c" "$(stats "$f" "$wall")"
}

chunks_of() { # <exact title>
  psql_db -tAc "SELECT count(*) FROM document_chunks c
                JOIN documents d ON d.id = c.document_id WHERE d.title = '$1'" | tr -d ' \r'
}

ingest_one() { # <payload-file> -> echoes seconds
  curl -s -o /dev/null -w '%{time_total}' -X POST "$BASE_URL/clients/$CLIENT_ID/documents" \
    -H 'Content-Type: application/json' --data @"$1"
}

# One set-based generation pass per scale: copy the pristine seed documents (snapshotted below)
# with fresh uuidv7 ids; chunks are copied with their embeddings, and the generated fts column
# recomputes itself on insert. This is the same grow-by-copying methodology as the exact-scan and
# 200k-clients measurements in docs/operating-notes.md.
grow_documents() { # <from-gen> <to-gen>
  psql_db <<SQL
CREATE TEMP TABLE m AS
  SELECT s.id AS old_id, g.g, uuidv7() AS new_id
  FROM loadtest_seed_documents s, generate_series($1, $2) AS g(g);
INSERT INTO documents (id, client_id, title, content, created_at)
  SELECT m.new_id, d.client_id, d.title || ' [copy ' || m.g || ']', d.content, d.created_at
  FROM m JOIN documents d ON d.id = m.old_id;
INSERT INTO document_chunks (document_id, chunk_index, content, embedding, embedding_model)
  SELECT m.new_id, c.chunk_index, c.content, c.embedding, c.embedding_model
  FROM m JOIN document_chunks c ON c.document_id = m.old_id;
SQL
}

grow_clients() { # <copies-per-seed-client>
  psql_db <<SQL
CREATE TEMP TABLE cm AS
  SELECT s.id AS old_id, g.g, uuidv7() AS new_id
  FROM loadtest_seed_clients s, generate_series(1, $1) AS g(g);
INSERT INTO clients (id, first_name, last_name, email, description, social_links, created_at)
  SELECT cm.new_id, c.first_name, c.last_name, 'copy' || cm.g || '.' || c.email,
         c.description, c.social_links, c.created_at
  FROM cm JOIN clients c ON c.id = cm.old_id;
SQL
}

# --- preflight ---------------------------------------------------------------------------------

if ! curl -sf "$BASE_URL/actuator/health" | grep -q '"UP"'; then
  echo "No healthy instance at $BASE_URL. Start one with: docker compose -p $PROJECT up -d" >&2
  exit 1
fi

DOCS=$(psql_db -tAc "SELECT count(*) FROM documents" | tr -d ' \r')
if [ "$DOCS" -gt 25 ]; then
  echo "This corpus has $DOCS documents — not a pristine seed. This script grows and writes to" >&2
  echo "the database; point it at a disposable stack: docker compose -p bench up -d" >&2
  exit 1
fi

psql_db -c "DROP TABLE IF EXISTS loadtest_seed_documents, loadtest_seed_clients;
            CREATE UNLOGGED TABLE loadtest_seed_documents AS SELECT id FROM documents;
            CREATE UNLOGGED TABLE loadtest_seed_clients   AS SELECT id FROM clients;" >/dev/null

say "Warmup (20 requests)"
for i in $(seq 1 20); do curl -s -o /dev/null "$BASE_URL/search?q=warmup+$i"; done

# --- search: seed scale ------------------------------------------------------------------------

say "Search at seed scale ($(corpus_label))"
for c in 1 5 10 20; do search_cell "$c"; done

# --- ingest scenarios at seed scale ------------------------------------------------------------

say "Ingest scenarios"
python3 - "$TMP" <<'PY'
import sys
para = ("The trustees reviewed the fund's allocation against the mandate agreed at the previous "
        "meeting, noting that the fixed income sleeve had drifted above its target weight after "
        "the rally in gilts. The committee resolved to rebalance gradually over the following "
        "month to avoid crystallising unnecessary transaction costs, and to revisit the currency "
        "hedging policy once the pending regulatory guidance had been published. ")
def prose(target):
    out, i = "", 0
    while len(out) < target:
        i += 1
        out += para.replace("previous", f"number {i}")
    return out[:target]
open(f"{sys.argv[1]}/doc10k.txt", "w").write(prose(9900))
open(f"{sys.argv[1]}/doc99k.txt", "w").write(prose(99000))
PY

CLIENT_ID=$(curl -s -X POST "$BASE_URL/clients" -H 'Content-Type: application/json' \
  -d '{"first_name":"Load","last_name":"Test","email":"load.test@example.test"}' | jq -r '.id')

jq -Rs '{title: "Load Test 10KB single", content: .}' "$TMP/doc10k.txt" > "$TMP/p10.json"
T=$(ingest_one "$TMP/p10.json")
printf '| One 10 KB document (the brief%s average), seed corpus | %s ms, %s chunks |\n' "'s" \
  "$(python3 -c "print(round($T * 1000))")" "$(chunks_of 'Load Test 10KB single')" >> "$INGEST_ROWS"
echo "  10 KB single: ${T}s"

for i in 1 2 3 4 5; do
  jq -Rs --arg t "Load Test 10KB concurrent $i" '{title: $t, content: .}' "$TMP/doc10k.txt" > "$TMP/pc$i.json"
done
t0=$(now)
printf '1\n2\n3\n4\n5\n' | xargs -P 5 -I{} curl -s -o /dev/null -w '%{time_total}\n' \
  -X POST "$BASE_URL/clients/$CLIENT_ID/documents" -H 'Content-Type: application/json' \
  --data @"$TMP/pc{}.json" > "$TMP/times.ing5"
t1=$(now)
WALL=$(python3 -c "print($t1 - $t0)")
printf '| Five 10 KB documents concurrently | per-request p50 %s, max %s; %s docs/s aggregate |\n' \
  "$(sort -n "$TMP/times.ing5" | awk 'NR==3 {printf "%.0f ms", $1*1000}')" \
  "$(sort -n "$TMP/times.ing5" | awk 'END {printf "%.0f ms", $1*1000}')" \
  "$(python3 -c "print(f'{5 / $WALL:.1f}')")" >> "$INGEST_ROWS"
echo "  5x 10 KB concurrent: wall ${WALL}s"

jq -Rs '{title: "Load Test 99KB cap", content: .}' "$TMP/doc99k.txt" > "$TMP/p99.json"
T=$(ingest_one "$TMP/p99.json")
printf '| One 99 KB document (just under the cap) | %s s, %s chunks |\n' \
  "$(python3 -c "print(f'{$T:.1f}')")" "$(chunks_of 'Load Test 99KB cap')" >> "$INGEST_ROWS"
echo "  99 KB single: ${T}s"

say "Mixed workload: searches at concurrency 10 for the whole duration of a 99 KB ingest"
jq -Rs '{title: "Load Test 99KB mixed", content: .}' "$TMP/doc99k.txt" > "$TMP/p99b.json"
curl -s -o /dev/null -X POST "$BASE_URL/clients/$CLIENT_ID/documents" \
  -H 'Content-Type: application/json' --data @"$TMP/p99b.json" &
INGEST_PID=$!
t0=$(now)
seq 1 "$REQUESTS" | xargs -P 10 -I{} curl -s -o /dev/null -w '%{time_total}\n' \
  "$BASE_URL/search?q=quarterly+portfolio+rebalancing+mixed+{}" > "$TMP/times.mixed"
t1=$(now)
wait "$INGEST_PID"
WALL=$(python3 -c "print($t1 - $t0)")
printf '| Search while the 99 KB ingest runs (%s requests, concurrency 10) | p50/p95/p99/max: %s |\n' \
  "$REQUESTS" "$(stats "$TMP/times.mixed" "$WALL" | sed 's/ | /, /g')" >> "$INGEST_ROWS"
echo "  during ingest -> $(stats "$TMP/times.mixed" "$WALL")"

# --- search: medium scale ----------------------------------------------------------------------

say "Growing corpus to medium scale (x50 documents, x1000 clients)"
grow_documents 1 49
grow_clients 999
settle
say "Search at medium scale ($(corpus_label))"
for c in 1 10; do search_cell "$c"; done

# --- search: large scale -----------------------------------------------------------------------

say "Growing corpus to large scale (x650 documents; takes a minute)"
grow_documents 50 649
settle
say "Search at large scale ($(corpus_label))"
for c in 1 10; do search_cell "$c"; done

jq -Rs '{title: "Load Test 10KB at scale", content: .}' "$TMP/doc10k.txt" > "$TMP/p10b.json"
T=$(ingest_one "$TMP/p10b.json")
printf '| One 10 KB document again, at the large corpus | %s ms — ingest cost is model inference, not corpus size |\n' \
  "$(python3 -c "print(round($T * 1000))")" >> "$INGEST_ROWS"
echo "  10 KB single at large corpus: ${T}s"

# --- report ------------------------------------------------------------------------------------

say "Rows for docs/load-and-limits.md: search"
echo '| Corpus | Concurrency | p50 | p95 | p99 | max | Throughput |'
echo '| --- | --- | --- | --- | --- | --- | --- |'
cat "$SEARCH_ROWS"

say "Rows for docs/load-and-limits.md: ingest"
echo '| Scenario | Result |'
echo '| --- | --- |'
cat "$INGEST_ROWS"
