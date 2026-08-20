#!/usr/bin/env bash
#
# Reports what a published JetBrains Toolbox build bundles, without needing Toolbox
# installed on the machine doing the reading.
#
# Three of this project's pinned versions -- kotlin, kotlinx-coroutines-core and
# kotlinx-serialization-json -- are not "keep them current" dependencies. Toolbox supplies
# all three at runtime, so the pinned value is a ceiling read out of the installed app's
# bin/lib, and raising one above what Toolbox loads is a NoSuchMethodError inside Toolbox
# that CI structurally cannot see, because CI only compiles.
#
# The instruction that used to guard that said "re-read bin/lib after a Toolbox upgrade".
# It had no trigger -- Toolbox self-upgraded 3.7.0.87111 -> 3.7.1.87197 on 2026-08-20
# mid-session and was noticed only because a log line changed its version prefix -- and it
# needed the app installed, so it could never run in CI. This replaces both halves with two
# published JetBrains endpoints:
#
#   the build feed      https://data.services.jetbrains.com/products/releases?code=TBA&latest=true
#   and, per build, a manifest of what that build bundles, whose URL the feed itself carries
#   at .TBA[0].downloads.thirdPartyLibrariesJson.link
#
# The manifest URL is read out of the feed rather than composed from the build number. Both
# forms work today; only one of them survives JetBrains moving the file.
#
# Validated before being relied on: for build 3.7.1.87197 the manifest reports Kotlin
# 2.4.10-RC2, kotlinx.coroutines 1.10.2 and kotlinx.serialization 1.9.0, and all three match
# bin/lib of the installed 3.7.1.87197 exactly.
#
# Two limits worth carrying:
#
#   * The feed reports what JetBrains has PUBLISHED, not what any machine has installed.
#     Those diverged for most of 2026-08-20. A new build here means "go and check", never
#     "the pins are now wrong".
#   * The manifest names libraries by DISPLAY NAME ("Kotlin", "kotlinx.coroutines"), not by
#     Maven coordinate. Matching is exact and a missing name is a hard failure, because the
#     dangerous outcome is a rename reading as "no change". Substring matching is not an
#     option: "Kotlin" occurs in 8 of the 119 entries in build 3.7.1.87197, most of them
#     unrelated libraries such as jackson-module-kotlin and kotlinx-rpc.
#
# The feed advertises a .sha256 beside the manifest. It is NOT usable: as measured
# 2026-08-20 it answers 403 with an S3 AccessDenied body, while the installer checksums
# under download.jetbrains.com answer 200. So nothing here verifies a checksum, and the
# tamper story is TLS and JetBrains' own hosting. Do not add a checksum step on the strength
# of the link existing in the feed.
#
# Usage:
#   toolbox-bundled-versions.sh build           # 3.7.1.87197
#   toolbox-bundled-versions.sh kotlin          # 2.4.10-RC2
#   toolbox-bundled-versions.sh coroutines      # 1.10.2
#   toolbox-bundled-versions.sh serialization   # 1.9.0
#   toolbox-bundled-versions.sh --all           # all four, as a table, on stderr-free stdout
#
# One value per call and nothing else on stdout, because updatecli takes stdout as the
# source value. Everything explanatory goes to stderr.
#
# Exit 0 a value was printed, 2 the check could not run (network, or the feed is not the
# shape this expects), 3 a library this expects is absent from the manifest. Read the 2 and
# the 3: neither means "no change", and a check that cannot run must not look like one that
# passed.

set -uo pipefail

for tool in curl jq; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "error: $tool is required but not installed." >&2
    echo "Install with: sudo apt-get install -y $tool" >&2
    exit 2
  fi
done

FEED_URL="https://data.services.jetbrains.com/products/releases?code=TBA&latest=true"

# The four values must all describe ONE build. Four independent fetches could straddle a
# release and record a build number from before it with library versions from after, which
# is a wrong answer that looks like a right one. Caching the feed and the manifest for the
# length of a run closes that window; it is not there for speed.
cache_dir="${TOOLBOX_RUNTIME_CACHE:-${TMPDIR:-/tmp}/xcpng-toolbox-runtime-$(id -u)}"
cache_ttl="${TOOLBOX_RUNTIME_CACHE_TTL:-300}"

fetch_cached() {
  # fetch_cached <url> <cache file name>; prints the local path.
  local url="$1" name="$2" path="$cache_dir/$2"

  if [ -s "$path" ] && [ "$cache_ttl" -gt 0 ]; then
    local age
    age=$(( $(date +%s) - $(stat -c %Y "$path" 2>/dev/null || echo 0) ))
    if [ "$age" -lt "$cache_ttl" ]; then
      echo "$path"
      return 0
    fi
  fi

  mkdir -p "$cache_dir" || { echo "error: cannot create $cache_dir" >&2; return 2; }
  if ! curl -fsSL --retry 3 --retry-delay 2 --max-time 30 -o "$path.tmp" "$url"; then
    echo "error: could not fetch $url" >&2
    rm -f "$path.tmp"
    return 2
  fi
  if ! jq -e . >/dev/null 2>&1 < "$path.tmp"; then
    echo "error: $url did not return JSON" >&2
    rm -f "$path.tmp"
    return 2
  fi
  mv "$path.tmp" "$path" || return 2
  echo "$path"
}

feed_path=$(fetch_cached "$FEED_URL" "feed.json") || exit 2

build=$(jq -r '.TBA[0].build // empty' < "$feed_path")
if [ -z "$build" ]; then
  echo "error: the release feed carries no .TBA[0].build; its shape has changed." >&2
  echo "       feed: $FEED_URL" >&2
  exit 2
fi

manifest_of() {
  local link
  link=$(jq -r '.TBA[0].downloads.thirdPartyLibrariesJson.link // empty' < "$feed_path")
  if [ -z "$link" ]; then
    echo "error: the feed entry for $build carries no thirdPartyLibrariesJson link." >&2
    exit 2
  fi
  fetch_cached "$link" "third-party-libraries-$build.json" || exit 2
}

# Our key -> the display name JetBrains uses in the manifest. Both halves matter: the left
# is what gradle/toolbox-runtime.yaml records, the right is a string owned by somebody else.
display_name_for() {
  case "$1" in
    kotlin)        echo "Kotlin" ;;
    coroutines)    echo "kotlinx.coroutines" ;;
    serialization) echo "kotlinx.serialization" ;;
    *)             return 1 ;;
  esac
}

bundled_version() {
  local key="$1" name value manifest
  name=$(display_name_for "$key") || { echo "error: unknown library key '$key'" >&2; exit 2; }
  manifest=$(manifest_of)

  # --arg, not string interpolation, and == rather than contains(). An exact match on the
  # whole name is the point of this function.
  value=$(jq -r --arg n "$name" 'map(select(.name == $n)) | .[0].version // empty' < "$manifest")

  if [ -z "$value" ]; then
    echo "error: build $build's manifest lists no library named exactly '$name'." >&2
    echo "       JetBrains has most likely renamed it. This is NOT 'no change': the pin" >&2
    echo "       for $key is a ceiling read out of what Toolbox bundles, and this run" >&2
    echo "       learned nothing about it. Find the new name in:" >&2
    echo "       $(jq -r '.TBA[0].downloads.thirdPartyLibrariesJson.link' < "$feed_path")" >&2
    exit 3
  fi
  echo "$value"
}

case "${1:-}" in
  build)
    echo "$build"
    ;;
  kotlin|coroutines|serialization)
    bundled_version "$1"
    ;;
  --all)
    printf 'build\t%s\n' "$build"
    for k in kotlin coroutines serialization; do
      printf '%s\t%s\n' "$k" "$(bundled_version "$k")" || exit $?
    done
    ;;
  *)
    echo "usage: $(basename "$0") build|kotlin|coroutines|serialization|--all" >&2
    exit 2
    ;;
esac
