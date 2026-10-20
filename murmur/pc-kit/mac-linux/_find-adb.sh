# Finds adb: in a platform-tools folder near this kit, ~/platform-tools, or on PATH.
here="$(cd "$(dirname "$0")" && pwd)"
for c in "$here/../../adb" "$here/../adb" "$HOME/platform-tools/adb" "$(command -v adb)"; do
  [ -x "$c" ] && ADB="$c" && break
done
[ -z "$ADB" ] && { echo "adb not found. See README.md step 1."; exit 1; }
LOGS="$here/../logs"; mkdir -p "$LOGS"
STAMP="$(date +%Y%m%d-%H%M%S)"
