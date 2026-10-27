#!/usr/bin/env bash
set -euo pipefail
root="${PIXELMC_GAME_ROOT:-/data}"
stage="$root/pixelmcmanager/fortune-rpc-hotfix-0.2.1"
old="$root/mods/dailyfortunes-forge-1.20.1-0.2.0.jar"
new="$root/mods/dailyfortunes-forge-1.20.1-0.2.1.jar"
old_sha=f9c5df546615cd8a7f854141f68335bd59c7432e9b614c0ccc6bb2715d65b1c2
new_sha=e66540ac58e53604a16aa0f10a7ac8db00885f46e3c40aee52cccb60ed95c40d
check() { [[ -f "$1" && "$(sha256sum "$1" | cut -d' ' -f1)" == "$2" ]]; }
check "$stage/dailyfortunes-forge-1.20.1-0.2.1.jar" "$new_sha"
if [[ -e "$new" ]]; then
  check "$new" "$new_sha"
  [[ ! -e "$old" ]]
else
  # Recover an interrupted move before trying again. Never accept an unknown original.
  if [[ ! -e "$old" && -e "$stage/previous/dailyfortunes-forge-1.20.1-0.2.0.jar" ]]; then
    check "$stage/previous/dailyfortunes-forge-1.20.1-0.2.0.jar" "$old_sha"
    cp "$stage/previous/dailyfortunes-forge-1.20.1-0.2.0.jar" "$old"
  fi
  check "$old" "$old_sha"
  mkdir -p "$stage/previous"
  cp "$stage/dailyfortunes-forge-1.20.1-0.2.1.jar" "$stage/.incoming.jar"
  check "$stage/.incoming.jar" "$new_sha"
  mv "$old" "$stage/previous/dailyfortunes-forge-1.20.1-0.2.0.jar"
  mv "$stage/.incoming.jar" "$new"
fi
manifest="$root/pixelmcmanager/unified-20261027/active.sha256"
temporary="$stage/.active.sha256.tmp"
sed -e '\|  mods/dailyfortunes-forge-1.20.1-0.2.0.jar$|d' -e '\|  mods/dailyfortunes-forge-1.20.1-0.2.1.jar$|d' "$manifest" > "$temporary"
printf '%s  %s\n' "$new_sha" 'mods/dailyfortunes-forge-1.20.1-0.2.1.jar' >> "$temporary"
mv "$temporary" "$manifest"
printf '%s\n' 'DailyFortunes 0.2.1 activated; AutoModpack rebuilds its content at this startup.' > "$stage/applied.txt"
