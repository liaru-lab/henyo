---
name: manage-henyo-change
description: Drive Henyo public-repository changes from public-safe GitHub Issue drafting through a codex branch, implementation, verification, privacy review, pull request creation, and authorized squash merge. Use for non-trivial Henyo feature, bug, documentation, test, build, or workflow changes that should be tracked in an Issue and merged through a pull request. Do not use for GitHub Releases or private distribution.
---

# Manage a Henyo Change

Deliver one cohesive public Henyo change from an approved plan to a verified
merge. Treat GitHub Issues as the source of truth and keep private evidence out
of every public surface.

## Establish the boundary

1. Read the repository-root `AGENTS.md` completely and follow it as the
   mandatory safety policy.
2. Read [references/privacy-checklist.md](references/privacy-checklist.md)
   completely before drafting public text or publishing a branch.
3. Identify the requested outcome, in-scope behavior, explicit exclusions,
   acceptance criteria, and required verification.
4. Distinguish read-only inspection, local implementation, GitHub writes,
   device mutation, merge, and release. Obtain authority for each material
   action that is not already explicit in the request.
5. Stop at the merge boundary. Public releases and private distribution require
   separate, explicitly authorized maintainer workflows.

## Draft and create the Issue

1. Draft the title and body before creating the Issue.
2. Include the goal, context, scope, exclusions, acceptance criteria, and
   verification plan. Keep implementation details only when they constrain the
   contract or safety boundary.
3. Use synthetic placeholders for examples. Never copy private values from a
   conversation, device, log, screenshot, local configuration, or prior task.
4. Run the privacy checklist over the complete draft. Show the draft before the
   external write when approval is required or requested.
5. Create the Issue only after the content and authority are settled. Record
   its number and URL for the branch and pull request.

## Create the work branch

1. Preserve unrelated tracked, untracked, and ignored user work.
2. Fetch the remote and require a clean, synchronized base branch unless the
   user explicitly chose another safe base.
3. Create `codex/<issue-number>-<slug>` with a short lowercase slug.
4. Never use destructive cleanup to manufacture a clean worktree. Stop and
   report an overlap that cannot be preserved safely.

## Implement and verify

1. Make the smallest change that satisfies the Issue. Keep application-specific
   behavior out of generic interfaces unless the Issue explicitly requires it.
2. Keep raw device evidence, logs, captures, identifiers, and private UI text
   under ignored `artifacts/`. Do not stage or quote them in public text.
3. Use granular development commits when they help diagnosis or review. Keep
   every commit within the Issue scope; remove fixup, debug, and unrelated
   changes before requesting merge.
4. Run verification proportionate to risk. Follow all Android deployment rules
   in `AGENTS.md`; never bypass a failed preflight.
5. Map results to every acceptance criterion. Do not treat dispatch, build, or
   installation success as proof of a separate application-level outcome.

## Audit before publishing the branch

Run the privacy checklist over all of these surfaces:

- proposed Issue, pull request, comment, and review text;
- tracked and staged files;
- the complete base-to-head diff;
- every reachable branch commit and commit message;
- generated, ignored, and untracked files that might be staged accidentally;
- the remote branch and GitHub text after publication.

Also require:

- `git diff --check` passes;
- the changed-file list matches the Issue scope;
- the commit list contains no fixup or unrelated work;
- no APK, checksum, signing sidecar, credential, raw capture, or local evidence
  is tracked or staged;
- findings report categories and paths only, with matched values redacted.

Treat automated pattern scans as supporting evidence, not proof. Perform a
semantic review of new proper nouns, identifiers, examples, and metadata.

## Create the pull request

1. Push only the reviewed branch.
2. Create a pull request that links the Issue with `Closes #<issue-number>`.
3. Summarize the public behavior and verification without exposing raw test
   inputs, device details, private screens, or environment-specific evidence.
4. Inspect the rendered pull request, changed files, commits, comments,
   reviews, mergeability, and checks. Repeat the privacy audit against the
   GitHub-visible result.
5. Address findings through the same branch and rerun affected verification.

## Merge and confirm

1. Merge only with explicit or clearly pre-authorized merge authority and only
   after acceptance criteria, reviews, checks, and required device verification
   are complete.
2. Prefer GitHub squash merge when several development commits represent one
   public change. Preserve the detailed development history in the pull
   request while adding one cohesive commit to `main`.
3. Do not rewrite or delete a branch unless authorized. Never use merge as an
   excuse to discard unrelated local work.
4. After merge, verify the pull request is merged, the Issue is closed as
   intended, the merge commit is reachable from the remote default branch, and
   local `main` can be synchronized without divergence.
5. Report the Issue, pull request, merge commit, verification summary, privacy
   result, branch disposition, and final worktree state.
