#!/usr/bin/env bash
set -euo pipefail
umask 077
stage="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
root="${PIXELMC_SERVICE_ROOT:-/data}"
(cd "$stage" && sha256sum --check SHA256SUMS)
if [[ "${1:-}" == '--check-payload' ]]; then exit 0; fi
if [[ -f "$stage/prepared.txt" ]]; then exit 0; fi
cd "$root"
jar="${1:?new service JAR required}"
config="${2:?configuration required}"
[[ "$config" == 'manager.json' ]]
# This JVM runs only after the old service has stopped and its native backup succeeded.
java -Xms16m -Xmx96m -cp "$stage/classes:$jar" DailyFortunesPrepare "$config" "$stage/fortunes-database.json" "$stage/manager.before.json"
date -u +%FT%TZ > "$stage/.prepared.tmp"
mv -- "$stage/.prepared.tmp" "$stage/prepared.txt"
