#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

# Optional parameters:
# 1: number of clients
# 2: number of waiters
# 3: table capacities, e.g. 2,2,4,5,5,4
# 4: initial ingredient count
# 5: maximum ingredient count
# 6: GUI mode name
# 7: rounds per client (0 or negative value = infinite loop)
# 8: restock interval in ms (0 = disable restocker)
# 9: ingredients added per restock
# 10: filet portions added per restock
# 11: soup portions added per restock
# 12: cutlery items washed per cycle
# 13: spoons washed per cycle
CLIENTS="${1:-20}"
WAITERS="${2:-3}"
TABLECAPS="${3:-2,2,4,5,5,4}"
ING="${4:-6}"
INGMAX="${5:-12}"
MODE="${6:-RESTAURANT}"
ROUNDS="${7:-0}"
RESTOCK_MS="${8:-1500}"
RESTOCK_ING_ADD="${9:-1}"
RESTOCK_FILET_ADD="${10:-1}"
RESTOCK_SOUP_ADD="${11:-1}"
WASH_CUTLERY="${12:-2}"
WASH_SPOONS="${13:-2}"

echo "[1/5] Compile..."
rm -rf out
mkdir -p out
javac -encoding UTF-8 -d out src/*.java

LOG="$(mktemp -t restaurant_server_log.XXXXXX)"

cleanup() {
  echo
  echo "[cleanup] killing background processes..."
  jobs -pr | xargs -r kill 2>/dev/null || true
  rm -f "$LOG" 2>/dev/null || true
}
trap cleanup EXIT

echo "[2/5] Start server..."
java -cp out RestaurantServer \
  --tableCaps "$TABLECAPS" \
  --waiters "$WAITERS" \
  --ingredients "$ING" \
  --ingredientsMax "$INGMAX" \
  | tee "$LOG" &
SERVER_PID=$!

for _ in $(seq 1 200); do
  if grep -q "PORT=" "$LOG"; then break; fi
  sleep 0.05
done

PORT="$(grep -m1 'PORT=' "$LOG" | sed -E 's/.*PORT=([0-9]+).*/\1/')"
if [[ -z "${PORT}" ]]; then
  echo "ERROR: Unable to read the server port from logs."
  echo "Log: $LOG"
  exit 1
fi

echo "[ok] PORT=$PORT"

echo "[3/5] Start GUI..."
java -cp out GuiApp "$PORT" "$MODE" &
GUI_PID=$!

if [[ "$RESTOCK_MS" != "0" ]]; then
  echo "[4/5] Start restocker..."
  java -cp out RestockerProcess "$PORT" "$RESTOCK_MS" "$RESTOCK_ING_ADD" "$RESTOCK_FILET_ADD" "$RESTOCK_SOUP_ADD" "$WASH_CUTLERY" "$WASH_SPOONS" >/dev/null 2>&1 &
else
  echo "[4/5] Restocker: OFF"
fi

echo "[5/5] Start clients ($CLIENTS x rounds=$ROUNDS)..."
for i in $(seq 1 "$CLIENTS"); do
  java -cp out ClientProcess "$PORT" "$i" "$ROUNDS" 1 >/dev/null 2>&1 &
  sleep 0.06
done

echo
echo "== RUNNING =="
echo "GUI: started"
echo "Server PID: $SERVER_PID"
echo "Stop: close the GUI window or press Ctrl+C in this terminal"
echo
wait "$GUI_PID"
