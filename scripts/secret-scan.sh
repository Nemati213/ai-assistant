#!/bin/sh
set -eu

if ! command -v git >/dev/null 2>&1; then
  echo "git is required for secret scanning" >&2
  exit 1
fi

if [ -z "$(git ls-files)" ]; then
  echo "No tracked files to scan."
  exit 0
fi

patterns='(sk-or-v1-[A-Za-z0-9_-]{20,}|ghp_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|-----BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----|[0-9]{8,10}:[A-Za-z0-9_-]{30,}|AKIA[0-9A-Z]{16})'

matches="$(
  git grep -n -I -E "$patterns" || true
)"

if [ -n "$matches" ]; then
  echo "Potential secrets found in tracked files:" >&2
  echo "$matches" >&2
  exit 1
fi

echo "Secret scan passed."
