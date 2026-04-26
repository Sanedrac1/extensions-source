#!/bin/bash
set -e

git config --global user.email "91925379+Sanedrac1@users.noreply.github.com"
git config --global user.name "Sanedrac-Bot"
git status
if [ -n "$(git status --porcelain)" ]; then
    git add .
    git commit -m "Update extensions repo"
    git push

else
    echo "No changes to commit"
fi
