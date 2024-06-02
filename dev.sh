#!/usr/bin/env sh
# workaround so devenv+flakes pick up .env when added to .gitignore
# from: https://mtlynch.io/notes/use-nix-flake-without-git/
nix develop --impure path:.
