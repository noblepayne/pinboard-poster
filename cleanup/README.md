# Git CI Commit Squasher

Collapses consecutive commits from a CI system or bot into single commits per run.

## Usage

```bash
./collapse_ci_commits.py <base_commit> <author_email> <commit_message>
```

## Parameters

- `<base_commit>` — Git reference to start from (e.g., `main`, `a5903f5`, `HEAD~10`)
- `<author_email>` — Email of the CI/bot account (e.g., `ci@example.com`)
- `<commit_message>` — Exact commit message to match (e.g., `Updating database.`)

## Example

```bash
./collapse_ci_commits.py main "noblepayne@users.noreply.github.com" "Updating database."
```

This will find all consecutive commits by that author with that exact message since `main`, and squash each run into a single commit.

## How It Works

1. Fetches all commits since the base commit
2. Groups consecutive commits matching the author and message
3. Marks all but the first in each group for fixup (squash with message discarded)
4. Runs an interactive rebase automatically
5. Logs progress and counts along the way

## Notes

- The rebase runs non-interactively — git will handle it automatically
- Each "run" of consecutive matching commits becomes one commit
- If runs are interrupted by other commits, they're treated as separate runs
- Works on the current repository in the current directory
