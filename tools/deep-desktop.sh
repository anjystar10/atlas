#!/usr/bin/env bash
# The desktop part of the deep test rig (deep.yml): the real Windows app's pages and main process, run on Linux under
# xvfb and crawled (desktop/test/crawl.mjs), then ESLint over desktop/ (desktop/test/eslint.deep.mjs).
# Usage: tools/deep-desktop.sh OUTDIR [--quick]   (from the repository root; never fails: findings are the output)
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=$(realpath -m "${1:-deep-out/desktop}"); shift || true
mkdir -p "$OUT"
cd "$ROOT/desktop"
if [ ! -x node_modules/.bin/electron ]; then npm ci --no-audit --no-fund > "$OUT/npm-ci.log" 2>&1 || { echo "npm ci failed"; tail -20 "$OUT/npm-ci.log"; }; fi
# only for the rig: not in package.json, so the app and its installer stay exactly as they are
npm i --no-save --no-audit --no-fund playwright-core@1.64.1 eslint@9.39.5 globals@16.5.0 > "$OUT/npm-rig.log" 2>&1 || tail -20 "$OUT/npm-rig.log"
command -v xvfb-run >/dev/null || { sudo apt-get update -qq && sudo apt-get install -y -qq xvfb > /dev/null; }
{ node -v; node -e "console.log('electron', require('electron/package.json').version)"; } > "$OUT/versions.txt" 2>&1

echo "== crawl"
timeout 2400 xvfb-run -a -s "-screen 0 2560x1440x24" node test/crawl.mjs "$OUT" "$@" > "$OUT/crawl.log" 2>&1
echo "crawl exit $?" >> "$OUT/crawl.log"
tail -5 "$OUT/crawl.log"

echo "== eslint"
npx eslint -c test/eslint.deep.mjs -f json -o "$OUT/eslint.json" '*.js' renderer scripts > "$OUT/eslint.log" 2>&1
echo "eslint exit $?" >> "$OUT/eslint.log"
python3 "$ROOT/tools/deep/eslint_summary.py" "$OUT/eslint.json" "$ROOT/desktop" > "$OUT/eslint-findings.md" 2>> "$OUT/eslint.log"
head -40 "$OUT/eslint-findings.md"
exit 0
