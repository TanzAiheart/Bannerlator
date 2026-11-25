#!/usr/bin/env bash
set -euo pipefail

apk=${1:?signed Bannerlator APK required}
pack=CH3XP-vanilla-bee4d1f24fc6.zip
sdk="$ANDROID_SDK_ROOT/build-tools/35.0.0"
output="$RUNNER_TEMP/Chivalrous_Heroes_XP_V2.apk"
stage="$RUNNER_TEMP/ch3xp-package"
patch="$stage/assets/ch3xp/patch"
windows="_ch3xp-vanilla/Win7 v1.6 SDL3 controller"
mkdir -p "$patch" "$stage/ddraw"

(cd "$RUNNER_TEMP/ch3xp-game" && sha256sum --check "$pack.sha256")
(cd "$windows" && sha256sum --check SHA256SUMS.txt)
cp "$windows"/{Shkxp.exe,Shkxp.exe.manifest,chxp-sdl3.dll,SDL3.dll,SDL3-LICENSE.txt} "$patch/"
cp app/src/main/assets/ch3xp/CH3XP_Touch_Controls.icp "$stage/assets/ch3xp/"
bash ci/fetch-ch3xp-proton.sh "$stage/assets/ch3xp"
mv "$RUNNER_TEMP/ch3xp-game/$pack" "$stage/assets/ch3xp/game.pack"

tar --zstd -xf app/src/main/assets/ddrawrapper/cnc-ddraw.tzst -C "$stage/ddraw"
mapfile -d '' wrappers < <(find "$stage/ddraw" -type f -iname ddraw.dll -print0)
usable=()
for wrapper in "${wrappers[@]}"; do
    if objdump -f "$wrapper" | grep -q 'file format pei-i386'; then
        usable+=("$wrapper")
    fi
done
if (( ${#usable[@]} == 0 )); then
    echo 'Could not find the 32-bit cnc-ddraw DLL.' >&2
    exit 1
fi
cp "${usable[0]}" "$patch/ddraw.dll"
printf '%s\n' '[ddraw]' 'renderer=gdi' 'fullscreen=false' 'windowed=true' > "$patch/ddraw.ini"

python3 - "$stage/assets/ch3xp/game.pack" <<'PY'
import json
import sys
from zipfile import ZipFile

with ZipFile(sys.argv[1]) as pack:
    manifest = json.loads(pack.read("manifest.json"))
    names = {name.casefold() for name in pack.namelist()}
    assert len(names) == len(pack.namelist()), "Duplicate archive names"
    assert {"shkxp.exe", "sh3diskidx.dat", "sh3diskimg.dat", "data/shkintro.sh3"} <= names
    assert all(entry["size"] == pack.getinfo(name).file_size for name, entry in manifest["files"].items())
PY

# Append the precompressed game archive, then realign the APK and sign it anew.
cp "$apk" "$stage/unaligned.apk"
mapfile -t signatures < <(unzip -Z1 "$apk" | grep -E '^META-INF/([^/]+\.(RSA|DSA|EC|SF)|MANIFEST\.MF)$' || true)
if (( ${#signatures[@]} > 0 )); then
    zip -q -d "$stage/unaligned.apk" "${signatures[@]}"
fi
(cd "$stage" && zip -q -0 -r unaligned.apk assets/ch3xp)
"$sdk/zipalign" -P 16 -f 4 "$stage/unaligned.apk" "$stage/aligned.apk"
JAVA_TOOL_OPTIONS=-Xmx4g "$sdk/apksigner" sign --ks "$PWD/keystore/testkey.p12" \
    --ks-type PKCS12 --ks-key-alias testkey --ks-pass pass:android --key-pass pass:android \
    --v2-signing-enabled true --v3-signing-enabled true \
    --out "$output" "$stage/aligned.apk"
JAVA_TOOL_OPTIONS=-Xmx4g "$sdk/apksigner" verify --verbose "$output"
"$sdk/zipalign" -c -P 16 4 "$output"
"$sdk/aapt2" dump badging "$output" > "$stage/badging.txt"
grep -F "package: name='com.ch3xp.banner.v2'" "$stage/badging.txt"
grep -F "launchable-activity: name='com.winlator.star.MainActivity'" "$stage/badging.txt"

python3 - "$output" "$stage/assets/ch3xp" <<'PY'
import hashlib
import sys
from pathlib import Path
from zipfile import ZIP_STORED, ZipFile

def digest(stream):
    sha256 = hashlib.sha256()
    with stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            sha256.update(chunk)
    return sha256.hexdigest()

payload = Path(sys.argv[2])
with ZipFile(sys.argv[1]) as apk:
    assert "assets/imagefs.tar.zst" in apk.namelist()
    assert any(n.startswith("lib/arm64-v8a/") for n in apk.namelist())
    assert apk.getinfo("assets/ch3xp/game.pack").compress_type == ZIP_STORED
    for path in payload.rglob("*"):
        if path.is_file():
            entry = "assets/ch3xp/" + path.relative_to(payload).as_posix()
            assert apk.namelist().count(entry) == 1, entry
            assert digest(apk.open(entry)) == digest(path.open("rb")), entry
    print("Verified embedded game ZIP, pinned Windows EXE/SDL3, GDI wrapper, and touch profile.")
PY
cd "$RUNNER_TEMP"
sha256sum Chivalrous_Heroes_XP_V2.apk > Chivalrous_Heroes_XP_V2.apk.sha256
