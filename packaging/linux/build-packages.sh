#!/usr/bin/env bash
# The four Linux packages of one version: sluice and sluice-desktop, each an rpm and a deb, each with its own Java
# runtime cut by jlink. CI's linux-packages job runs it on a tag; packaging/linux/check-packages.sh runs it locally.
#
#   build-packages.sh <version>      from the repository's root, after `mvn package`
#
# NFPM names the nfpm binary (default: nfpm on the PATH). NFPM_RPM_KEY_FILE and NFPM_RPM_PASSPHRASE sign the rpms
# (packaging/nfpm*.yaml); left empty, nfpm signs nothing.
#
# Writes target/sluice-all.jar, target/sluice-desktop-all.jar, target/runtime, target/desktop-runtime and
# target/sluice.rpm, sluice.deb, sluice-desktop.rpm, sluice-desktop.deb.
set -euo pipefail

VERSION="${1:?usage: build-packages.sh <version>}"
NFPM="${NFPM:-nfpm}"
export SLUICE_VERSION="$VERSION"
export NFPM_RPM_KEY_FILE="${NFPM_RPM_KEY_FILE:-}"

shopt -s nullglob
jar() {
  local found=("$1"/target/sluice-"$1"-*-all.jar)
  [ ${#found[@]} -eq 1 ] || {
    echo "::error::expected one $1/target/sluice-$1-*-all.jar, found ${#found[@]}: run mvn clean package first" >&2
    exit 1
  }
  echo "${found[0]}"
}

mkdir -p target
cp "$(jar cli)" target/sluice-all.jar
cp "$(jar desktop)" target/sluice-desktop-all.jar
java -jar target/sluice-all.jar --version

# jlink refuses to write over a runtime, and a stale one would keep modules a newer jar no longer names.
rm -rf target/runtime target/desktop-runtime
jlink_runtime() {
  jlink --add-modules "$1" --strip-debug --no-header-files --no-man-pages --output "$2"
}

# The command: what jdeps sees the classes name, and jdk.charsets, which a non-UTF-8 locale loads by lookup alone.
modules=$(jdeps --print-module-deps --ignore-missing-deps --multi-release 25 target/sluice-all.jar)
jlink_runtime "$modules,jdk.charsets" target/runtime
target/runtime/bin/java -jar target/sluice-all.jar --version

# The window: JavaFX is in the jar, on the class path, so a JDK that bundles JavaFX must not have jdeps name its
# modules. Added to what jdeps sees, what is reached by lookup alone: jdk.unsupported (JavaFX's Unsafe),
# jdk.crypto.ec (TLS to GitHub), jdk.accessibility (a screen reader), jdk.localedata, jdk.charsets, jdk.zipfs.
desktop=$(jdeps --print-module-deps --ignore-missing-deps --multi-release 25 target/sluice-desktop-all.jar \
  | tr ',' '\n' | grep -v '^javafx\.' | paste -sd, -)
jlink_runtime "${desktop:+$desktop,}java.desktop,jdk.unsupported,jdk.zipfs,jdk.charsets,jdk.crypto.ec,jdk.localedata,jdk.accessibility" \
  target/desktop-runtime
# A window cannot open here: check-packages.sh starts one under xvfb.
target/desktop-runtime/bin/java --list-modules | grep -q '^java.desktop@' \
  || { echo "::error::the window's runtime lacks java.desktop"; exit 1; }

"$NFPM" package -f packaging/nfpm.yaml -p rpm -t target/sluice.rpm
"$NFPM" package -f packaging/nfpm.yaml -p deb -t target/sluice.deb
"$NFPM" package -f packaging/nfpm-desktop.yaml -p rpm -t target/sluice-desktop.rpm
"$NFPM" package -f packaging/nfpm-desktop.yaml -p deb -t target/sluice-desktop.deb
