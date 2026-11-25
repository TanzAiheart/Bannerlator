#!/usr/bin/env bash
set -euo pipefail

out=${1:?output directory required}
mkdir -p "$out"
curl --fail --silent --show-error --location --retry 4 \
    https://github.com/The412Banner/winlator-contents/releases/download/bionic-layers-20261124-v18/proton-11.0-3-x86_64.wcp \
    --output "$out/proton.wcp"
printf '%s  %s\n' 9aff125e403aa6c14fc33b59fcff72e25e5b1955002e14f5ffd59391cdca9c58 "$out/proton.wcp" | sha256sum -c -
python3 - "$out" <<'PY'
import json
import re
import sys
import subprocess
import tarfile
from pathlib import Path

out = Path(sys.argv[1])
archive_path = out / "proton.wcp"
with archive_path.open("rb") as source:
    zstd = source.read(4) == bytes.fromhex("28b52ffd")
if zstd:
    with (out / "proton-validation.tar").open("wb") as unpacked:
        subprocess.run(["zstd", "-dc", str(archive_path)], stdout=unpacked, check=True)
    archive_path = out / "proton-validation.tar"
with tarfile.open(archive_path, "r:*") as archive:
    members = {member.name.removeprefix("./"): member for member in archive.getmembers()}
    profile_bytes = archive.extractfile(members["profile.json"]).read()
    profile = json.loads(profile_bytes)
    assert profile["type"].lower() == "proton", profile
    assert re.fullmatch(r"[0-9.]+(?:-[0-9.]+)?-x86_64", profile["versionName"]), profile
    wine = profile["wine"]
    assert "prefixPack.txz" in members and members["prefixPack.txz"].isfile()
    for key in ("binPath", "libPath"):
        folder = wine[key].removeprefix("./").rstrip("/")
        assert any(path.startswith(folder + "/") for path in members), wine
    assert any(path.endswith("/i386-windows/ntdll.dll") for path in members), "Missing WoW64 x86 ntdll"
    with archive.extractfile(members["prefixPack.txz"]) as prefix_stream:
        with tarfile.open(fileobj=prefix_stream, mode="r|xz") as prefix:
            paths = {member.name.removeprefix("./") for member in prefix}
        assert any(path.endswith("/system.reg") for path in paths), "Missing Windows registry in prefix"
    (out / "proton-profile.json").write_bytes(profile_bytes)
    print("Verified Proton layer, 32-bit Wine DLLs, and Windows prefix:", profile["versionName"])
PY
rm -f "$out/proton-validation.tar"
