#!/usr/bin/env bash
# Build npm tarballs from the UniForm revision pinned by this repository.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk_root="$repo_root/vendor/uni-form"
if [[ ! -f "$sdk_root/package.json" ]]; then
  printf '缺少 UniForm submodule；請先執行 git submodule update --init vendor/uni-form。\n' >&2
  exit 2
fi
if [[ "$(node -p 'process.versions.node.split(".")[0]')" != 24 ]]; then
  printf 'UniForm 前端建置需要 Node 24。\n' >&2
  exit 2
fi
cd "$sdk_root"
expected_pnpm="$(node -p 'JSON.parse(require("node:fs").readFileSync("package.json", "utf8")).packageManager.replace(/^pnpm@/, "")')"
if ! command -v pnpm >/dev/null 2>&1 || [[ "$(pnpm --version)" != "$expected_pnpm" ]]; then
  printf '請先安裝 UniForm 指定的 pnpm：npm install --global pnpm@%s\n' "$expected_pnpm" >&2
  exit 2
fi
pnpm install --frozen-lockfile
bash scripts/pack-npm.sh
# Native pnpm builds may use different gzip implementations across platforms.
# Normalize compression with the project's Node runtime so npm's lockfile checks
# the same tarball bytes locally and in CI; package contents are unchanged.
node --input-type=module - <<'JS'
import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { gunzipSync, gzipSync } from 'node:zlib';
for (const name of readdirSync('dist/npm').filter(name => name.endsWith('.tgz'))) {
  const path = `dist/npm/${name}`;
  writeFileSync(path, gzipSync(gunzipSync(readFileSync(path)), { level: 9 }));
}
JS
