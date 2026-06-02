#!/usr/bin/env bash
set -euo pipefail

SUBMODULE_PATH="third_party/croc-src"
REMOTE_URL="https://github.com/schollz/croc"
BRANCH="main"

cd "$(git rev-parse --show-toplevel)"

if git submodule status "$SUBMODULE_PATH" &>/dev/null; then
    echo "Removing existing submodule '$SUBMODULE_PATH'..."
    git submodule deinit -f "$SUBMODULE_PATH"
    git rm -rf "$SUBMODULE_PATH"
    rm -rf .git/modules/"$SUBMODULE_PATH"
fi

echo "Adding submodule '$SUBMODULE_PATH' from $REMOTE_URL ($BRANCH)..."
git submodule add -b "$BRANCH" "$REMOTE_URL" "$SUBMODULE_PATH"

echo "Running go mod vendor inside '$SUBMODULE_PATH'..."
cd "$SUBMODULE_PATH"
go mod vendor
cd -

echo "Done. Submodule '$SUBMODULE_PATH' is ready."
echo "Review changes with:  git diff --cached"
echo "Commit with:          git commit -m 'Update croc submodule (orig)'"
