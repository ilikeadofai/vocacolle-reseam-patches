#!/usr/bin/env python3
"""Inspect the emitted VocaColle 7.48.0 bridges and verify all APKS component signatures.

This is an artifact check, not an Android runtime/UI test.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("apks", type=Path)
parser.add_argument("--build-tools", type=Path, required=True)
parser.add_argument("--output-dir", type=Path, required=True)
args = parser.parse_args()
out = args.output_dir
out.mkdir(parents=True, exist_ok=True)
prefix = "Lio/github/ilikeadofai/vocacolle/core/library/"
app = "Ljp/nicovideo/nicobox/ui/"
wanted = {prefix + name + ";" for name in ["NativePlaylists", "CreateNativePlaylist", "AddNativePlaylistItem"]}
wanted |= {app + "NicoboxApplication;", app + "player/PlayerFragment;", "Lofc;", "Lqcc;"}
dumps = {}
signers = set()
components = []

def inspect_dex(data, name):
    dex = out / name
    dex.write_bytes(data)
    process = subprocess.Popen([str(args.build_tools / "dexdump"), "-d", str(dex)],
                               stdout=subprocess.PIPE, text=True, errors="replace")
    owner, lines = None, []

    def save():
        if owner:
            assert owner not in dumps, f"Duplicate class {owner}"
            dumps[owner] = "".join(lines)
            (out / (owner[1:-1].replace("/", "_") + ".txt")).write_text(dumps[owner])

    for line in process.stdout:
        if line.startswith("Class #"):
            save()
            owner, lines = None, []
        found = re.search(r"Class descriptor  : '([^']+)'", line)
        if found and found[1] in wanted:
            owner = found[1]
        if owner:
            lines.append(line)
    save()
    assert process.wait() == 0, f"dexdump failed on {name}"

with zipfile.ZipFile(args.apks) as package:
    assert package.testzip() is None
    for name in package.namelist():
        if not name.endswith(".apk"):
            continue
        data = package.read(name)
        apk = out / Path(name).name
        apk.write_bytes(data)
        verified = subprocess.run([str(args.build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)],
                                  capture_output=True, text=True)
        (out / (apk.name + ".signature.txt")).write_text(verified.stdout + verified.stderr)
        assert verified.returncode == 0, f"APK signature failed: {apk.name}"
        signers.add(re.search(r"certificate SHA-256 digest: ([0-9a-f]+)", verified.stdout)[1])
        components.append(apk.name)
        with zipfile.ZipFile(io.BytesIO(data)) as contents:
            assert contents.testzip() is None, f"APK ZIP corrupted: {apk.name}"
            for entry in contents.namelist():
                if re.fullmatch(r"classes\d*\.dex", entry):
                    inspect_dex(contents.read(entry), apk.stem + "-" + entry)

assert len(components) == 4 and len(signers) == 1, "Unexpected 7.48.0 component/signature set"
assert wanted == dumps.keys(), f"Missing classes: {wanted - dumps.keys()}"

def method(data, name):
    return data.split("      name          : '" + name + "'", 1)[1].split("\n    #", 1)[0]

native = dumps[prefix + "NativePlaylists;"]
create = dumps[prefix + "CreateNativePlaylist;"]
add = dumps[prefix + "AddNativePlaylistItem;"]
checks = {
    "create_and_add_implement_native_function2": all("#0              : 'Lni5;'" in x for x in [create, add]),
    "create_calls_native_repository": "Ly8c;.U:(Ljava/lang/String;Lofc;ZLep2;)" in method(create, "invoke"),
    "create_uses_LOCALLIST": "Lofc;.b:Lofc;" in method(create, "invoke") and
        "Lofc;.b:Lofc;" in dumps["Lofc;"].split('"LOCALLIST"', 1)[1].split('"WATCH_LATER"', 1)[0],
    "add_calls_native_repository_with_video_id": "Lxj9$b;.<init>:(Ljava/lang/String;)V" in method(add, "invoke") and
        "Ly8c;.T:(Lxj9;JLep2;)" in method(add, "invoke"),
    "native_runBlocking_default_context": "Lh21;.b:(Lyr2;Lni5;ILjava/lang/Object;)" in method(native, "runOperation") and
        "#int 1" in method(native, "runOperation"),
    "existing_owner_playlists": "Ly8c;.H:()Ls91;" in method(native, "entries") and "Ls91;.s:()Ljava/util/List;" in method(native, "entries"),
    "owner_playlist_id_field": "Lx1b;.a:J" in method(native, "entryId"),
    "all_item_rows_field": "Lv8c;.c:Ljava/util/List;" in method(native, "entryItems"),
    "media_id_field": "Lz8c;.d:Ljava/lang/String;" in method(native, "itemVideoId"),
    "original_picker_request_and_delegate": "Lkcc$a;.<init>:(Lxj9;Ljava/lang/Long;Lqcc;)V" in method(native, "showPicker") and
        "Llbc;.a:(Lis9;Lkcc;Lxh5;Lzh5;Lzh5;Lzh5;)V" in method(native, "showPicker"),
    "picker_source_OPTION_MENU": "Lqcc;.b:Lqcc;" in method(native, "showPicker") and
        "Lqcc;.b:Lqcc;" in dumps["Lqcc;"].split('"OPTION_MENU"', 1)[1].split('"PLAYLIST_EDIT"', 1)[0],
    "repository_observer": ".repositoryReady:(Ljava/lang/Object;)V" in method(dumps[app + "NicoboxApplication;"], "F"),
    "player_state_capture_and_clear": all(x in dumps[app + "player/PlayerFragment;"] for x in
                                          [".rememberPicker:(Ljava/lang/Object;)V", ".clearPicker:()V"]),
}
result = {"app_version": "7.48.0", "dex_checks": checks, "apk_signatures": "verified", "zip_integrity": "verified",
          "components": components, "signer_sha256": next(iter(signers)), "runtime_ui": "not_verified",
          "apks_sha256": hashlib.sha256(args.apks.read_bytes()).hexdigest()}
(out / "verification.json").write_text(json.dumps(result, indent=2) + "\n")
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'}: {name}")
assert all(checks.values()), "Native playlist DEX check failed"
print("PASS: all four APK signatures and ZIP integrity; runtime UI remains unverified")
