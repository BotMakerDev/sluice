#!/usr/bin/env bash
# Checks the Linux packages end to end, as a user gets them, before a tag builds the real ones:
#
#   1. the four packages built here (build-packages.sh), the rpms signed by a throwaway key;
#   2. the signed dnf and apt site built from them (.github/scripts/build-repo.sh), served on localhost;
#   3. in a Fedora and a Debian container, `install.sh --desktop` against that site, gpgcheck on;
#   4. `sluice --version`, then sluice-desktop started under Xvfb and the screen saved, for a person to read.
#
#   packaging/linux/check-packages.sh [--no-build]      from the repository's root, on x86_64 Linux
#
# Needs podman (or docker), gpg, python3, curl, and JDK 25 with mvn on the PATH; nfpm is fetched when absent.
# --no-build reuses target/*.rpm and target/*.deb from an earlier run, signed by its key (target/check/signing.asc).
# Writes target/check/site and target/check/<distro>.png; prints PASS or the step that failed.
set -euo pipefail

PORT="${CHECK_PORT:-8765}"
NFPM_VERSION="${NFPM_VERSION:-2.43.0}"
DISTROS=(fedora:latest debian:stable)
WORK=target/check
ENGINE="$(command -v podman || command -v docker || true)"
[ -n "$ENGINE" ] || { echo "error: podman or docker is needed" >&2; exit 1; }
[ -f pom.xml ] && [ -d packaging ] || { echo "error: run from the sluice repository's root" >&2; exit 1; }

build=1
[ "${1:-}" = "--no-build" ] && build=0

export GNUPGHOME="$PWD/$WORK/gnupg"
mkdir -p "$WORK"

if [ "$build" = 1 ]; then
  # A key of this check alone, never the org's: what it signs is installed in throwaway containers only.
  rm -rf "$GNUPGHOME" && mkdir -m 700 "$GNUPGHOME"
  gpg --batch --quiet --passphrase '' --quick-gen-key 'sluice check <check@localhost>' rsa3072 sign never
  gpg --batch --armor --export-secret-keys > "$WORK/signing.asc"

  NFPM="$(command -v nfpm || true)"
  if [ -z "$NFPM" ]; then
    curl -fsSL "https://github.com/goreleaser/nfpm/releases/download/v${NFPM_VERSION}/nfpm_${NFPM_VERSION}_Linux_x86_64.tar.gz" \
      | tar -xz -C "$WORK" nfpm
    NFPM="$PWD/$WORK/nfpm"
  fi
  version="$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout | sed 's/-SNAPSHOT$//')"
  # clean: a jar of an earlier version left in target/ would be a second one to choose from.
  mvn -B -q clean package -DskipTests
  NFPM="$NFPM" NFPM_RPM_KEY_FILE="$PWD/$WORK/signing.asc" NFPM_RPM_PASSPHRASE='' \
    bash packaging/linux/build-packages.sh "$version"
fi
KEY_ID="$(gpg --batch --list-keys --with-colons | awk -F: '/^pub/ {print $5; exit}')"

# The site, built where createrepo_c and apt-ftparchive are a package away.
rm -rf "$WORK/site" "$WORK/artifacts" && mkdir -p "$WORK/artifacts"
cp target/sluice.rpm target/sluice.deb target/sluice-desktop.rpm target/sluice-desktop.deb "$WORK/artifacts/"
# The container's gpg imports the exported key into a home of its own: the host's home stays the host's, owned by
# its user and in its GnuPG's format.
"$ENGINE" run --rm -v "$PWD:/src:Z" -w /src -e GPG_KEY_ID="$KEY_ID" \
  -e SLUICE_SIGN=1 -e SLUICE_LOCAL_SITE=1 -e PAGES_URL="http://localhost:$PORT" debian:stable bash -c '
    set -e
    apt-get update -qq && apt-get install -y -qq createrepo-c apt-utils gnupg > /dev/null
    export GNUPGHOME=$(mktemp -d)
    gpg --batch --quiet --import '"$WORK"'/signing.asc
    bash .github/scripts/build-repo.sh '"$WORK"'/artifacts '"$WORK"'/site vcheck
    chown -R '"$(id -u):$(id -g)"' '"$WORK"'/site'

if curl -fs -o /dev/null "http://localhost:$PORT/"; then
  echo "error: port $PORT already answers: stop what serves it, or set CHECK_PORT" >&2
  exit 1
fi
python3 -m http.server "$PORT" -d "$WORK/site" > "$WORK/http.log" 2>&1 &
server=$!
trap 'kill $server 2>/dev/null || true' EXIT
# Up when it serves this site's own key, not merely when something answers.
for _ in $(seq 20); do
  curl -fs -o /dev/null "http://localhost:$PORT/sluice.asc" && break
  kill -0 $server 2>/dev/null || { cat "$WORK/http.log" >&2; exit 1; }
  sleep 0.5
done
curl -fs -o /dev/null "http://localhost:$PORT/sluice.asc" || { echo "error: the site did not come up" >&2; exit 1; }

for distro in "${DISTROS[@]}"; do
  name="${distro%%:*}"
  echo "==> $distro"
  "$ENGINE" run --rm --network host -v "$PWD/$WORK:/out:Z" -e SLUICE_REPO_URL="http://localhost:$PORT" "$distro" bash -c '
    set -e
    if command -v dnf > /dev/null; then
      dnf install -y -q curl xorg-x11-server-Xvfb xwd ImageMagick procps-ng > /dev/null
    else
      apt-get update -qq && apt-get install -y -qq curl xvfb x11-apps imagemagick procps > /dev/null
    fi
    curl -fsSL "$SLUICE_REPO_URL/install.sh" | bash -s -- --desktop
    sluice --version
    test -f /usr/share/applications/sluice-desktop.desktop
    Xvfb :99 -screen 0 1280x860x24 & sleep 2
    DISPLAY=:99 sluice-desktop > /out/'"$name"'-desktop.log 2>&1 & app=$!
    sleep 20
    kill -0 $app || { cat /out/'"$name"'-desktop.log; echo "sluice-desktop exited"; exit 1; }
    xwd -root -silent -display :99 | convert xwd:- /out/'"$name"'.png'
  echo "    screen: $WORK/$name.png"
done
echo PASS
