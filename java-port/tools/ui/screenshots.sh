#!/usr/bin/env bash
#
# The Phase 10 screenshot smoke matrix.
#
# Renders every screen on a real GL backend at several device frames and writes
# a PNG for each.  Nothing here asserts: a screenshot suite that fails on a
# one-pixel difference gets switched off within a month.  What it proves is that
# every screen renders at every shape without throwing, and it leaves a set of
# images a person can flip through in a few seconds.
#
# The layout ASSERTIONS live in UiLayoutTest, which runs headless and is where a
# regression should be caught.  This is the evidence, not the gate.
#
# Usage:  tools/ui/screenshots.sh [output-dir]
#
set -u

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${1:-$ROOT/build/ui-screenshots}"
mkdir -p "$OUT"

SCREENS="menu settings shop talents playing bosses paused gameover"
DEVICES="desktop phone209 portrait"

cd "$ROOT" || exit 1
echo "building..."
./gradlew :lwjgl3:installDist -q || { echo "build failed"; exit 1; }

RUN="$ROOT/lwjgl3/build/install/lwjgl3/bin/lwjgl3"
[ -x "$RUN" ] || RUN="$RUN.bat"

fail=0
for device in $DEVICES; do
  for screen in $SCREENS; do
    file="$OUT/$device-$screen.png"
    printf '%-10s %-9s ' "$device" "$screen"
    if "$RUN" --device "$device" --screen "$screen" --mode endless \
              --frames 12 --screenshot "$file" --no-vsync >"$OUT/.log" 2>&1; then
      if [ -f "$file" ]; then echo "ok"; else echo "NO IMAGE"; fail=1; fi
    else
      echo "FAILED"; sed -n '1,20p' "$OUT/.log"; fail=1
    fi
  done
done

# One more with the layout overlay on, which is the image worth looking at when
# something is off: it shows the safe area and both bounds of every control.
"$RUN" --device phone209 --screen shop --mode endless --ui-debug \
       --frames 12 --screenshot "$OUT/phone209-shop-debug.png" --no-vsync \
       >"$OUT/.log" 2>&1 && echo "phone209   shop      ok (debug overlay)"

rm -f "$OUT/.log"
echo
echo "wrote $(ls -1 "$OUT"/*.png 2>/dev/null | wc -l) images to $OUT"
exit $fail
