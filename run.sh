#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

# Named scenarios:
#   NORMAL             balanced default simulation
#   RUSH_HOUR          more clients and faster restocking
#   LIMITED_RESOURCES  visible waiting caused by scarce resources
#   NO_RESTOCK         resources are consumed without renewal
#   SHORT_DEMO         one short cycle per client, useful for quick screenshots
#
# Usage examples:
#   ./run.sh
#   ./run.sh --list-modes
#   ./run.sh RUSH_HOUR
#   ./run.sh LIMITED_RESOURCES 28
#
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
CLIENTS="20"
WAITERS="3"
TABLECAPS="2,2,4,5,5,4"
ING="6"
INGMAX="12"
MODE="NORMAL"
ROUNDS="0"
RESTOCK_MS="1500"
RESTOCK_ING_ADD="1"
RESTOCK_FILET_ADD="1"
RESTOCK_SOUP_ADD="1"
WASH_CUTLERY="2"
WASH_SPOONS="2"

print_modes() {
  cat <<'EOF'
Available modes:
  NORMAL             Balanced restaurant simulation.
  RUSH_HOUR          More clients, larger room, faster restocking.
  LIMITED_RESOURCES  Fewer waiters and resources, so queues are easy to see.
  NO_RESTOCK         Restocker disabled, resources gradually run out.
  SHORT_DEMO         One client cycle, useful for quick screenshots.

Examples:
  ./run.sh NORMAL
  ./run.sh RUSH_HOUR
  ./run.sh LIMITED_RESOURCES
  ./run.sh NO_RESTOCK
  ./run.sh SHORT_DEMO

You can still override positional parameters after a mode, for example:
  ./run.sh RUSH_HOUR 40
EOF
}

apply_mode_defaults() {
  MODE="$(printf '%s' "$1" | tr '[:lower:]' '[:upper:]')"
  case "$MODE" in
    NORMAL|RESTAURANT)
      MODE="NORMAL"
      CLIENTS="20"
      WAITERS="3"
      TABLECAPS="2,2,4,5,5,4"
      ING="6"
      INGMAX="12"
      ROUNDS="0"
      RESTOCK_MS="1500"
      RESTOCK_ING_ADD="1"
      RESTOCK_FILET_ADD="1"
      RESTOCK_SOUP_ADD="1"
      WASH_CUTLERY="2"
      WASH_SPOONS="2"
      ;;
    RUSH_HOUR|STRESS|STRESS_TEST)
      MODE="RUSH_HOUR"
      CLIENTS="35"
      WAITERS="4"
      TABLECAPS="2,2,4,4,6,6"
      ING="8"
      INGMAX="16"
      ROUNDS="0"
      RESTOCK_MS="900"
      RESTOCK_ING_ADD="2"
      RESTOCK_FILET_ADD="2"
      RESTOCK_SOUP_ADD="2"
      WASH_CUTLERY="3"
      WASH_SPOONS="3"
      ;;
    LIMITED_RESOURCES|LIMITED|SCARCE)
      MODE="LIMITED_RESOURCES"
      CLIENTS="24"
      WAITERS="2"
      TABLECAPS="2,2,4"
      ING="3"
      INGMAX="8"
      ROUNDS="0"
      RESTOCK_MS="2500"
      RESTOCK_ING_ADD="1"
      RESTOCK_FILET_ADD="1"
      RESTOCK_SOUP_ADD="1"
      WASH_CUTLERY="1"
      WASH_SPOONS="1"
      ;;
    NO_RESTOCK|WITHOUT_RESTOCK)
      MODE="NO_RESTOCK"
      CLIENTS="18"
      WAITERS="3"
      TABLECAPS="2,2,4,4"
      ING="4"
      INGMAX="8"
      ROUNDS="0"
      RESTOCK_MS="0"
      RESTOCK_ING_ADD="0"
      RESTOCK_FILET_ADD="0"
      RESTOCK_SOUP_ADD="0"
      WASH_CUTLERY="0"
      WASH_SPOONS="0"
      ;;
    SHORT_DEMO|DEMO)
      MODE="SHORT_DEMO"
      CLIENTS="12"
      WAITERS="3"
      TABLECAPS="2,4,4"
      ING="10"
      INGMAX="10"
      ROUNDS="1"
      RESTOCK_MS="1000"
      RESTOCK_ING_ADD="2"
      RESTOCK_FILET_ADD="2"
      RESTOCK_SOUP_ADD="2"
      WASH_CUTLERY="2"
      WASH_SPOONS="2"
      ;;
    *)
      echo "Unknown mode: $1"
      echo
      print_modes
      exit 1
      ;;
  esac
}

if [[ "${1:-}" == "--list-modes" || "${1:-}" == "--modes" ]]; then
  print_modes
  exit 0
fi

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  print_modes
  exit 0
fi

if [[ $# -gt 0 && ! "$1" =~ ^[0-9]+$ ]]; then
  apply_mode_defaults "$1"
  shift
fi

CLIENTS="${1:-$CLIENTS}"
WAITERS="${2:-$WAITERS}"
TABLECAPS="${3:-$TABLECAPS}"
ING="${4:-$ING}"
INGMAX="${5:-$INGMAX}"
MODE="${6:-$MODE}"
ROUNDS="${7:-$ROUNDS}"
RESTOCK_MS="${8:-$RESTOCK_MS}"
RESTOCK_ING_ADD="${9:-$RESTOCK_ING_ADD}"
RESTOCK_FILET_ADD="${10:-$RESTOCK_FILET_ADD}"
RESTOCK_SOUP_ADD="${11:-$RESTOCK_SOUP_ADD}"
WASH_CUTLERY="${12:-$WASH_CUTLERY}"
WASH_SPOONS="${13:-$WASH_SPOONS}"

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
echo "[mode] $MODE | clients=$CLIENTS waiters=$WAITERS tables=$TABLECAPS ingredients=$ING/$INGMAX restock_ms=$RESTOCK_MS rounds=$ROUNDS"
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
