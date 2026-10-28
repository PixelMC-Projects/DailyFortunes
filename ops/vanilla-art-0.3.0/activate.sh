#!/usr/bin/env bash
# Run only from the normal startup wrapper, before launching the game JVM.
set -euo pipefail
root="${PIXELMC_GAME_ROOT:-/data}"
stage="$root/pixelmcmanager/vanilla-art-0.3.0"
cd "$root"
source "$stage/activation.env"
check() { [[ -f "$1" && "$(sha256sum "$1" | cut -d' ' -f1)" == "$2" ]]; }
check "$stage/$new_name" "$new_sha"
check "$stage/$pack_name" "$pack_sha"
check "$stage/automodpack.next.json" "$next_config_sha"
check "$stage/active.next.sha256" "$next_manifest_sha"
manifest="$root/pixelmcmanager/unified-20261027/active.sha256"
config="$root/automodpack/automodpack-server.json"
previous="$stage/previous"
if [[ -f "$stage/applied.txt" ]]; then
  check "$root/mods/$new_name" "$new_sha"
  check "$root/resourcepacks/$pack_name" "$pack_sha"
  [[ ! -e "$root/mods/$old_name" ]]
  exit 0
fi
restore() {
  [[ -f "$previous/ready" ]] || return 0
  cp "$previous/$old_name" "$root/mods/$old_name"
  cp "$previous/automodpack.json" "$config"
  cp "$previous/active.sha256" "$manifest"
  rm -f "$root/mods/$new_name" "$root/resourcepacks/$pack_name"
}
# Re-enter safely after an interrupted activation; no player data is touched.
if [[ -f "$previous/ready" ]]; then restore; fi
check "$root/mods/$old_name" "$old_sha"
check "$config" "$old_config_sha"
check "$manifest" "$old_manifest_sha"
[[ ! -e "$root/mods/$new_name" && ! -e "$root/resourcepacks/$pack_name" ]]
mapfile -t installed < <(find "$root/mods" -maxdepth 1 -name 'dailyfortunes-*.jar' -print)
[[ "${#installed[@]}" == 1 && "${installed[0]}" == "$root/mods/$old_name" ]]
mkdir -p "$previous" "$root/resourcepacks"
cp "$root/mods/$old_name" "$previous/$old_name"
cp "$config" "$previous/automodpack.json"
cp "$manifest" "$previous/active.sha256"
touch "$previous/ready"
trap 'status=$?; restore; exit "$status"' ERR
cp "$stage/$new_name" "$stage/.incoming.jar"
check "$stage/.incoming.jar" "$new_sha"
rm "$root/mods/$old_name"
mv "$stage/.incoming.jar" "$root/mods/$new_name"
cp "$stage/$pack_name" "$root/resourcepacks/$pack_name"
cp "$stage/automodpack.next.json" "$stage/.sync.tmp"
mv "$stage/.sync.tmp" "$config"
cp "$stage/active.next.sha256" "$stage/.manifest.tmp"
mv "$stage/.manifest.tmp" "$manifest"
check "$root/mods/$new_name" "$new_sha"
check "$root/resourcepacks/$pack_name" "$pack_sha"
check "$config" "$next_config_sha"
check "$manifest" "$next_manifest_sha"
printf '%s\n' 'DailyFortunes 0.3.0 activated. Vanilla default; Classic resource pack available but not enabled.' > "$stage/applied.txt"
trap - ERR
