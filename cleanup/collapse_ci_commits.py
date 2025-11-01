#!/usr/bin/env python3
"""
Git CI Commit Squasher: Collapses consecutive CI commits into single commits per run.
Clojure-style: plain data, pure functions, simple composition.
"""

import sys
import logging
from typing import TypedDict
import pygit2
import subprocess
import tempfile
import os

log = logging.getLogger(__name__)


class Commit(TypedDict):
    oid: str
    short_id: str
    author_email: str
    message: str


class Config(TypedDict):
    base_commit: str
    author_email: str
    message: str
    repo_path: str


class SquashPlan(TypedDict):
    commits: list
    squash_set: set
    run_count: int
    total_squash_count: int


def commit_from_pygit2(c):
    """Convert pygit2 commit to plain dict"""
    short = c.short_id.decode() if isinstance(c.short_id, bytes) else c.short_id
    return {
        "oid": str(c.id),
        "short_id": short,
        "author_email": c.author.email,
        "message": c.message.strip(),
    }


def validate_config(base, author, msg):
    """Validate and return config or exit"""
    if not base or not author or not msg:
        log.error("Missing required arguments")
        sys.exit(1)
    return {
        "base_commit": base,
        "author_email": author,
        "message": msg,
        "repo_path": ".",
    }


def load_repo(path):
    """Load repo or fail"""
    try:
        return pygit2.Repository(path)
    except KeyError:
        log.error(f"Not in a git repository: {path}")
        sys.exit(1)


def resolve_base_commit(repo, base_ref):
    """Resolve base commit or fail"""
    try:
        commit = repo.revparse_single(base_ref)
        short = (
            commit.short_id.decode()
            if isinstance(commit.short_id, bytes)
            else commit.short_id
        )
        log.info(f"Base commit: {short} - {commit.message.strip()}")
        return commit
    except KeyError:
        log.error(f"Base commit '{base_ref}' not found")
        sys.exit(1)


def fetch_commits_since_base(repo, base_commit):
    """Fetch all commits from base to HEAD"""
    walker = repo.walk(
        repo.head.target, pygit2.GIT_SORT_TOPOLOGICAL | pygit2.GIT_SORT_REVERSE
    )
    walker.hide(base_commit.id)
    commits = [commit_from_pygit2(c) for c in walker]
    log.info(f"Found {len(commits)} total commits since base")
    return commits


def partition_ci_runs(commits, target_author, target_message):
    """Partition commits into runs of consecutive CI commits"""
    if not commits:
        return []

    runs = []
    current_run = []

    for c in commits:
        is_ci = c["author_email"] == target_author and c["message"] == target_message
        if is_ci:
            current_run.append(c)
        else:
            if current_run:
                runs.append(current_run)
                current_run = []

    if current_run:
        runs.append(current_run)

    return runs


def build_squash_set(runs):
    """Build set of oids to squash (all but first in each run)"""
    squash_oids = set()
    for run in runs:
        if len(run) > 1:
            squash_oids.update(c["oid"] for c in run[1:])
    return squash_oids


def log_plan_summary(runs, squash_set):
    """Log what we're about to do"""
    total_ci = sum(len(run) for run in runs)
    log.info(f"Found {len(runs)} run(s) of CI commits ({total_ci} commits total)")

    for i, run in enumerate(runs, 1):
        squash_count = len(run) - 1
        log.info(
            f"  Run {i}: {len(run)} commits, will squash {squash_count} into the first"
        )

    log.info(f"Will squash {len(squash_set)} total commits")


def make_squash_plan(commits, runs):
    """Create plan for rebase"""
    if not runs:
        log.info("No matching CI commits found")
        return {
            "commits": commits,
            "squash_set": set(),
            "run_count": 0,
            "total_squash_count": 0,
        }

    squash_set = build_squash_set(runs)
    log_plan_summary(runs, squash_set)

    return {
        "commits": commits,
        "squash_set": squash_set,
        "run_count": len(runs),
        "total_squash_count": len(squash_set),
    }


def render_todo_lines(plan):
    """Render rebase todo from plan"""
    lines = []
    for c in plan["commits"]:
        action = "fixup" if c["oid"] in plan["squash_set"] else "pick"
        lines.append(f"{action} {c['short_id']} {c['message']}")
    return "\n".join(lines) + "\n"


def write_todo_file(content):
    """Write todo content to temp file, return path"""
    f = tempfile.NamedTemporaryFile(delete=False, mode="w", suffix=".txt")
    f.write(content)
    f.close()
    return f.name


def execute_rebase(repo_path, base_commit_ref, todo_path):
    """Execute git rebase with todo file"""
    log.info("Starting interactive rebase...")

    env = os.environ.copy()
    env["GIT_SEQUENCE_EDITOR"] = f"cat {todo_path} >"

    try:
        result = subprocess.run(
            ["git", "rebase", "-i", base_commit_ref],
            cwd=repo_path,
            env=env,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            log.error(f"Rebase failed:\n{result.stderr}")
            sys.exit(1)
    except Exception as e:
        log.error(f"Rebase error: {e}")
        sys.exit(1)
    finally:
        try:
            os.unlink(todo_path)
        except OSError:
            pass


def main():
    logging.basicConfig(level=logging.DEBUG, format="%(levelname)s: %(message)s")

    if len(sys.argv) != 4:
        print(f"Usage: {sys.argv[0]} <base_commit> <author_email> <commit_message>")
        print()
        print("Example:")
        print(f"  {sys.argv[0]} main 'ci@example.com' 'CI: test run'")
        sys.exit(1)

    # Load and validate
    config = validate_config(sys.argv[1], sys.argv[2], sys.argv[3])
    log.info(
        f"Squashing commits by '{config['author_email']}' with message: '{config['message']}'"
    )

    repo = load_repo(config["repo_path"])
    base_commit = resolve_base_commit(repo, config["base_commit"])

    # Fetch and analyze
    commits = fetch_commits_since_base(repo, base_commit)
    if not commits:
        log.warning("No commits to process")
        return

    runs = partition_ci_runs(commits, config["author_email"], config["message"])
    plan = make_squash_plan(commits, runs)

    if plan["total_squash_count"] == 0:
        log.info("No CI commits to squash")
        return

    # Execute
    todo = render_todo_lines(plan)
    todo_path = write_todo_file(todo)
    execute_rebase(repo.workdir, config["base_commit"], todo_path)

    log.info(
        f"✓ Complete. Squashed {plan['total_squash_count']} commits across {plan['run_count']} run(s)"
    )


if __name__ == "__main__":
    main()
