#!/usr/bin/env bash
# Builds the signed dnf and apt repositories published to this repository's GitHub Pages, out of the .rpm and the
# .deb the tag's CI just built. A user then installs with `curl -fsSL <pages>/install.sh | sudo bash`, or with the
# snippets on the landing page, and every later version arrives with the rest of the system's updates.
#
#   build-repo.sh <artifacts-dir> <site-dir> <tag>
#
# Adapted from botmaker-cli's script of the same name (2026-11-10). The packages are hosted on Pages themselves, as
# there: each is tens of MB with its jlink runtime, far under the Pages limit, so nothing points clients elsewhere.
#
# ONLY THE LATEST RELEASE. This is an upgrade channel, not an archive: every older version stays downloadable from
# the GitHub Releases, and each deploy is a fresh Pages artifact.
#
# SIGNING: repodata/repomd.xml.asc for dnf, a clearsigned InRelease and a detached Release.gpg for apt. dnf also
# checks the rpm's own header (`gpgcheck`), which the CI's release job signs (packaging/nfpm.yaml). Unsigned (no
# key: a dry run, a fork) the repositories are still built and the generated snippets turn the checks off, so what
# is published agrees with itself; that path is for trying the script, not for a release.
set -euo pipefail

ARTIFACTS="${1:?usage: build-repo.sh <artifacts-dir> <site-dir> <tag>}"
SITE="${2:?usage: build-repo.sh <artifacts-dir> <site-dir> <tag>}"
TAG="${3:?usage: build-repo.sh <artifacts-dir> <site-dir> <tag>}"

REPO_SLUG="${GITHUB_REPOSITORY:-BotMakerDev/sluice}"
# Pages serves <owner>.github.io/<repo>, lowercased. PAGES_URL overrides it for a site served locally.
PAGES_URL="${PAGES_URL:-https://$(echo "${REPO_SLUG%%/*}" | tr '[:upper:]' '[:lower:]').github.io/${REPO_SLUG##*/}}"

shopt -s nullglob
rpms=("${ARTIFACTS}"/*.rpm)
debs=("${ARTIFACTS}"/*.deb)
[ ${#rpms[@]} -gt 0 ] || { echo "::error::no .rpm found in ${ARTIFACTS}"; exit 1; }
[ ${#debs[@]} -gt 0 ] || { echo "::error::no .deb found in ${ARTIFACTS}"; exit 1; }
RPM="${rpms[0]}"
DEB="${debs[0]}"

SIGNING=0
if [ "${SLUICE_SIGN:-0}" = "1" ] && [ -n "${GPG_KEY_ID:-}" ]; then
  SIGNING=1
else
  echo "::warning::signing not configured: publishing repositories nothing can verify."
fi

# gpg in batch and loopback mode, as ci.yml imports the key.
gpg_run() {
  gpg --batch --yes --pinentry-mode loopback --passphrase "${GPG_PASSPHRASE:-}" -u "${GPG_KEY_ID}" "$@"
}

mkdir -p "${SITE}/rpm" "${SITE}/deb/pool/main/s/sluice"
# Each package under a name of its version: a fixed one would be a URL whose bytes change from release to release,
# and right after a deploy the Pages cache could serve the old package under the new index's checksum.
VERSION="${TAG#v}"

# --- dnf ---------------------------------------------------------------------------------------------------------
cp "${RPM}" "${SITE}/rpm/sluice-${VERSION}.x86_64.rpm"
# --general-compress-type, not --compress-type: the latter leaves primary.xml on the default. gz: every dnf reads it.
createrepo_c --general-compress-type gz "${SITE}/rpm"
if [ "${SIGNING}" = "1" ]; then
  gpg_run --detach-sign --armor -o "${SITE}/rpm/repodata/repomd.xml.asc" "${SITE}/rpm/repodata/repomd.xml"
fi

# --- apt ---------------------------------------------------------------------------------------------------------
# The package carries an x86_64 runtime, so it is amd64's alone, and install.sh's sources line says [arch=amd64].
mkdir -p "${SITE}/deb/dists/stable/main/binary-amd64"
cp "${DEB}" "${SITE}/deb/pool/main/s/sluice/sluice_${VERSION}_amd64.deb"
(
  cd "${SITE}/deb"
  # Origin BotMakerDev below is what install.sh's apt pin names (Debian's own `sluice` is another program).
  # Filename: in Packages is relative to this directory, which is why apt-ftparchive runs from here.
  apt-ftparchive packages pool > dists/stable/main/binary-amd64/Packages
  gzip -9kf dists/stable/main/binary-amd64/Packages
  # Release hashes every file under dists/stable, so it is written outside that tree and moved in: written in place,
  # apt-ftparchive would hash its own half-written output.
  apt-ftparchive \
    -o APT::FTPArchive::Release::Origin=BotMakerDev \
    -o APT::FTPArchive::Release::Label=sluice \
    -o APT::FTPArchive::Release::Suite=stable \
    -o APT::FTPArchive::Release::Codename=stable \
    -o APT::FTPArchive::Release::Architectures=amd64 \
    -o APT::FTPArchive::Release::Components=main \
    -o APT::FTPArchive::Release::Description="sluice release channel" \
    release dists/stable > "${TMPDIR:-/tmp}/Release.$$"
  mv "${TMPDIR:-/tmp}/Release.$$" dists/stable/Release
)
if [ "${SIGNING}" = "1" ]; then
  gpg_run --clearsign -o "${SITE}/deb/dists/stable/InRelease" "${SITE}/deb/dists/stable/Release"
  gpg_run --detach-sign --armor -o "${SITE}/deb/dists/stable/Release.gpg" "${SITE}/deb/dists/stable/Release"
fi

# --- the public key and the .repo file ---------------------------------------------------------------------------
# `signed-by=` takes an ASCII-armored key when the file is named .asc, so one export serves apt and `rpm --import`.
if [ "${SIGNING}" = "1" ]; then
  gpg --export --armor "${GPG_KEY_ID}" > "${SITE}/sluice.asc"
  rpm_gpg=$'gpgcheck=1\nrepo_gpgcheck=1\ngpgkey='"${PAGES_URL}/sluice.asc"
  apt_opts="[arch=amd64 signed-by=/etc/apt/keyrings/sluice.asc] "
else
  rpm_gpg=$'gpgcheck=0\nrepo_gpgcheck=0'
  apt_opts="[arch=amd64 trusted=yes] "
fi

cat > "${SITE}/sluice.repo" <<EOF
[sluice]
name=sluice
baseurl=${PAGES_URL}/rpm
enabled=1
${rpm_gpg}
EOF

# --- the one-command installer -----------------------------------------------------------------------------------
# Copied, never generated: what is reviewed at packaging/linux/install.sh is byte for byte what a user pipes into a
# root shell. Its default site is written there, not substituted here, so it is checked against this one.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALLER="${SCRIPT_DIR}/../../packaging/linux/install.sh"
[ -f "${INSTALLER}" ] || { echo "::error::installer not found at ${INSTALLER}"; exit 1; }
if [ -z "${SLUICE_LOCAL_SITE:-}" ]; then
  grep -qF "SLUICE_REPO_URL:-${PAGES_URL}}" "${INSTALLER}" || {
    echo "::error::install.sh's default site is not ${PAGES_URL}: update packaging/linux/install.sh."
    exit 1
  }
fi
install -m 755 "${INSTALLER}" "${SITE}/install.sh"

ONELINER="curl -fsSL ${PAGES_URL}/install.sh | sudo bash"

DNF_SNIPPET="sudo curl -fsSL -o /etc/yum.repos.d/sluice.repo ${PAGES_URL}/sluice.repo
sudo dnf install sluice"

APT_SNIPPET="sudo install -d -m 755 /etc/apt/keyrings
sudo curl -fsSL -o /etc/apt/keyrings/sluice.asc ${PAGES_URL}/sluice.asc
echo \"deb ${apt_opts}${PAGES_URL}/deb stable main\" | sudo tee /etc/apt/sources.list.d/sluice.list
printf 'Package: sluice\nPin: release o=BotMakerDev\nPin-Priority: 1001\n' | sudo tee /etc/apt/preferences.d/sluice
sudo apt-get update &amp;&amp; sudo apt-get install sluice"

# --- landing page ------------------------------------------------------------------------------------------------
# The organization site's stylesheet and copy button, as every BotMaker project page uses; nothing else is fetched.
cat > "${SITE}/index.html" <<EOF
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>sluice — package repository</title>
<link rel="stylesheet" href="https://botmakerdev.github.io/assets/style.css">
</head>
<body>
<h1>sluice</h1>
<p class="sub">Releases a Maven project split over the git submodules of one umbrella repository. dnf and apt
repositories for <strong>${TAG}</strong>: install once, then update with the rest of the system.</p>

<h2>One command</h2>
<pre><code>${ONELINER}</code></pre>
<p>Fedora, RHEL and Debian, Ubuntu, x86_64. <a href="install.sh">Read it first</a> if you would rather see what
you pipe into a root shell; it registers the repository below and installs from it, and re-running it is safe.</p>

<h2>Fedora / RHEL, by hand</h2>
<pre><code>${DNF_SNIPPET}</code></pre>

<h2>Debian / Ubuntu, by hand</h2>
<pre><code>${APT_SNIPPET}</code></pre>

<h2>Anywhere else</h2>
<p>Windows: the MSI, or <code>sluice-windows.zip</code> with its scoop manifest. Any OS with Java 25:
<code>java -jar sluice-all.jar</code>. All on the
<a href="https://github.com/${REPO_SLUG}/releases">Releases</a> page.</p>

<footer>
<p>The package carries its own Java runtime at <code>/usr/lib/sluice/</code> and requires git and Maven, which
a release runs.</p>
<p>This repository carries the <strong>latest release only</strong>: it is an upgrade channel, not an
archive.</p>
</footer>
<script src="https://botmakerdev.github.io/assets/copy.js" defer></script>
</body>
</html>
EOF

echo "Site built at ${SITE} ($(du -sh "${SITE}" | cut -f1)), advertising ${TAG} at ${PAGES_URL}"
