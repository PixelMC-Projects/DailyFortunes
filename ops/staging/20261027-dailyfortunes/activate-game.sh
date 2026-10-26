#!/usr/bin/env bash
set -euo pipefail
umask 077
root="${PIXELMC_GAME_ROOT:-/data}"
stage="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
name='dailyfortunes-forge-1.20.1-0.1.2.jar'
cd "$root"
if [[ "${1:-}" == '--check-payload' ]]; then (cd "$stage" && sha256sum --check SHA256SUMS); exit; fi
if [[ -f "$stage/applied.txt" ]]; then exit 0; fi
(cd "$stage" && sha256sum --check SHA256SUMS)
if [[ -e "$stage/activation.pending" ]]; then
  echo '[DailyFortunes staging] Interrupted activation needs review; game JVM was not started.' >&2; exit 1
fi
if [[ ! -f "$root/pixelmcmanager/deploy-manager-ui-0.5.2-20261026/applied.txt"
      || ! -f "$root/pixelmcmanager/deploy-titles-ui-0.4.2-20261026/applied.txt" ]]; then
  echo '[DailyFortunes staging] Earlier Manager/Titles activation is pending; extension deferred.'; exit 0
fi
if ! sha256sum --check "$stage/expected-future.sha256"; then
  echo '[DailyFortunes staging] Prior startup files changed; review the pending extension.'; exit 0
fi
shopt -s nullglob
existing=(mods/*dailyfortunes*.jar mods/*DailyFortunes*.jar)
if [[ ${#existing[@]} -ne 0 || -e config/dailyfortunes/server.json ]]; then
  echo '[DailyFortunes staging] Unexpected existing extension files; installation deferred.'; exit 0
fi
gson="$root/libraries/com/google/code/gson/gson/2.10/gson-2.10.jar"
if ! java -Xms16m -Xmx64m -cp "$stage/classes:$gson" DailyFortunesReadiness "$root/config/pixelmcmanager-bridge.json"; then exit 0; fi
backup="$stage/before-activation"
mkdir -p -- "$backup" config/dailyfortunes
cp -p -- automodpack/automodpack-server.json "$backup/automodpack-server.json"
printf '%s\n' "$name" > "$stage/activation.pending"
rollback() {
  trap - ERR INT TERM
  set +e
  rm -f -- "mods/$name" mods/.dailyfortunes.new config/dailyfortunes/.server.new config/dailyfortunes/server.json automodpack/.dailyfortunes.new
  rm -f -- "$stage/applied.txt" "$stage/.applied.tmp"
  cp -p -- "$backup/automodpack-server.json" automodpack/automodpack-server.json
  if sha256sum --check "$stage/expected-future.sha256"; then
    rm -f -- "$stage/activation.pending"
    echo '[DailyFortunes staging] Previous files restored; installation remains pending.'; exit 0
  fi
  echo '[DailyFortunes staging] Rollback unconfirmed; startup stopped for review.' >&2; exit 1
}
trap rollback ERR INT TERM
cp -- "$stage/server.json" config/dailyfortunes/.server.new
mv -- config/dailyfortunes/.server.new config/dailyfortunes/server.json
cp -- "$stage/automodpack-server.next.json" automodpack/.dailyfortunes.new
mv -- automodpack/.dailyfortunes.new automodpack/automodpack-server.json
# Publish the loadable JAR only after both configuration files are in place.
cp -- "$stage/$name" mods/.dailyfortunes.new
mv -- mods/.dailyfortunes.new "mods/$name"
date -u +%FT%TZ > "$stage/.applied.tmp"
mv -- "$stage/.applied.tmp" "$stage/applied.txt"
rm -f -- "$stage/activation.pending"
trap - ERR INT TERM
echo '[DailyFortunes staging] Mod and AutoModpack selection activated before the game JVM starts.'
