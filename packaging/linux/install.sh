#!/usr/bin/env bash
# sluice: one-command install on Fedora/RHEL (dnf) and Debian/Ubuntu (apt), x86_64.
#
#   curl -fsSL https://botmakerdev.github.io/sluice/install.sh | sudo bash
#
# It does what the landing page says to do by hand: register the signed package repository on this repository's
# GitHub Pages and install from it. It is short and plain on purpose, because it is piped into a root shell.
#
# Not generated: .github/scripts/build-repo.sh copies this file to the site root as it is, after checking that the
# default site below is the one it publishes.
#
# Everything is in main(), called on the last line: bash reads a piped script as it runs it, so a package manager
# or a maintainer script reading stdin would otherwise eat the rest of this file. A download cut short runs nothing.
#
# Re-running is safe: a registered repository is written again, and an installed package is upgraded or left.
set -euo pipefail

# The site to install from. SLUICE_REPO_URL points it at a staging or locally served site.
BASE_URL="${SLUICE_REPO_URL:-https://botmakerdev.github.io/sluice}"

RELEASES_URL="https://github.com/BotMakerDev/sluice/releases"

die() { echo "error: $*" >&2; exit 1; }
info() { echo "==> $*"; }

# The site's public key, or a refusal that says why: only a signed site has one.
fetch_key() {
  curl -fsSL -o "$1" "${BASE_URL}/sluice.asc" \
    || die "no signing key at ${BASE_URL}/sluice.asc: this site is unsigned, and this script installs from a signed one only"
}

main() {
  [ "$(id -u)" = "0" ] || die "run as root: pipe into 'sudo bash', or 'sudo $0'"

  command -v curl >/dev/null 2>&1 || die "curl is required (install it, or download a package from $RELEASES_URL)"

  # The packages carry an x86_64 Java runtime. Refusing here says why, where apt would only say "no candidate".
  local arch
  arch="$(uname -m)"
  case "$arch" in
    x86_64|amd64) ;;
    *) die "no package for $arch: sluice's packages are x86_64 only. With Java 25, run sluice-all.jar from $RELEASES_URL" ;;
  esac

  if command -v dnf >/dev/null 2>&1 || command -v yum >/dev/null 2>&1; then
    local pm
    pm="$(command -v dnf || command -v yum)"
    # The key in rpm's database before the first install, as Studio's installer does, so no import prompt waits
    # on a terminal this pipe does not have. Kept where the .repo below names it, a root-only directory.
    info "Importing the signing key"
    install -d -m 755 /etc/pki/rpm-gpg
    fetch_key /etc/pki/rpm-gpg/sluice.asc
    rpm --import /etc/pki/rpm-gpg/sluice.asc

    info "Registering the sluice repository for ${pm##*/}"
    # Written here, not downloaded: both checks are on whatever the site serves. Dropped in rather than added by
    # `dnf config-manager`, whose dnf4 and dnf5 spellings differ.
    printf '[sluice]\nname=sluice\nbaseurl=%s/rpm\nenabled=1\ngpgcheck=1\nrepo_gpgcheck=1\ngpgkey=file:///etc/pki/rpm-gpg/sluice.asc\n' \
      "$BASE_URL" > /etc/yum.repos.d/sluice.repo

    info "Installing"
    "$pm" install -y sluice </dev/null

  elif command -v apt-get >/dev/null 2>&1; then
    info "Registering the sluice repository for apt"
    install -d -m 755 /etc/apt/keyrings
    # signed-by takes an ASCII-armored key when the file is named .asc: the same export rpm imports.
    fetch_key /etc/apt/keyrings/sluice.asc
    echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/sluice.asc] ${BASE_URL}/deb stable main" \
      > /etc/apt/sources.list.d/sluice.list
    # Debian and Ubuntu ship an unrelated `sluice` (a pipe rate limiter, 0.03.01), which apt ranks above 0.2.0. The
    # pin makes this repository's win, whatever the two versions (found 2026-11-10, in a Debian container).
    printf 'Package: sluice\nPin: release o=BotMakerDev\nPin-Priority: 1001\n' > /etc/apt/preferences.d/sluice

    # Over the distribution's sluice, apt calls the change a downgrade, which -y refuses without --allow-downgrades.
    local installed
    installed="$(dpkg-query -W -f='${db:Status-Abbrev}${Maintainer}' sluice 2>/dev/null || true)"
    case "$installed" in
      ii*BotMakerDev*|"") ;;
      ii*) info "Replacing the installed sluice, an unrelated program of the same name (a pipe rate limiter)" ;;
    esac

    info "Installing"
    apt-get update </dev/null
    DEBIAN_FRONTEND=noninteractive apt-get install -y --allow-downgrades sluice </dev/null

  else
    die "no supported package manager found (dnf, yum or apt-get).
With Java 25, sluice-all.jar runs anywhere: $RELEASES_URL"
  fi

  info "Done: $(sluice --version </dev/null)"
  info "Updates arrive with the rest of the system; re-running this script works too."
}

main "$@"
