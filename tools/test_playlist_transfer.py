#!/usr/bin/env python3
"""Run the Android-independent native playlist transfer checks with Gradle's cached Kotlin compiler.

Run ./gradlew bundle first to populate the compiler cache. No Android device is needed.
"""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parent.parent
cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"


def jar(group, artifact, version=None):
    base = cache / group / artifact
    candidates = sorted(base.glob(f"{version or '*'}/**/{artifact}-*.jar"))
    if not candidates:
        raise SystemExit(f"Missing {artifact}; run ./gradlew bundle first")
    return candidates[-1]


version = "2.4.20"
stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", version)
compiler = [jar("org.jetbrains.kotlin", name, version) for name in [
    "kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-script-runtime", "kotlin-daemon-embeddable",
]] + [jar("org.jetbrains.kotlin", "kotlin-reflect"), jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"), jar("org.jetbrains", "annotations")]
source = root / "apps/vocacolle/extensions/core/src/main/kotlin/io/github/ilikeadofai/vocacolle/core/library/PlaylistTransfer.kt"
test = root / "tools/tests/PlaylistTransferTest.kt"
with tempfile.TemporaryDirectory(prefix="vocacolle-playlist-test-") as temp:
    subprocess.run(["java", "-cp", os.pathsep.join(map(str, compiler)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib", "-no-reflect", "-classpath", str(stdlib), "-jvm-target", "17",
                    "-d", temp, str(source), str(test)], check=True)
    subprocess.run(["java", "-cp", os.pathsep.join([temp, str(stdlib)]),
                    "io.github.ilikeadofai.vocacolle.core.library.PlaylistTransferTestKt"], check=True)
