# Public Change Privacy Checklist

Use this checklist before every GitHub write and repeat it against the rendered
remote result. Audit categories and decision rules; never preserve the private
values that motivated a check.

## Core rules

- Never place an actual private value in this skill, a committed search list,
  an allowlist, a test fixture, an example, or an audit report.
- Never assume a value is safe because it appeared in an earlier conversation,
  command, log, screenshot, Issue, or commit.
- Never echo a matched sensitive value. Report only its category, affected
  public file or surface, and `[redacted]`.
- Use synthetic placeholders such as `<DEVICE_IDENTIFIER>`,
  `<LOCAL_USERNAME>`, `<IP_ADDRESS>`, `<PORT>`, `<PAIRING_CODE>`,
  `<LATITUDE>`, and `<LONGITUDE>`.
- Use documentation-reserved domains and addresses only when a literal example
  is necessary. Prefer placeholders when a literal adds no value.
- Treat automated scans as incomplete. Always review meaning, context, proper
  nouns, exact identifiers, and metadata manually.

## Information categories

Review additions and public text for unnecessary instances of:

| Category | Includes |
| --- | --- |
| `personal_identity` | Personal names, private handles, email addresses, account identifiers, or biographical details |
| `device_identity` | Device aliases, serials, hardware identifiers, hostnames, or stable device-specific labels |
| `network_identity` | Public or private addresses, ports, private DNS names, network ranges, or connection endpoints |
| `credential_material` | Passwords, tokens, pairing codes, cookies, authorization headers, private keys, keystores, or recovery data |
| `location_information` | Coordinates, precise place names, routes, home/work markers, or screens that reveal a person's location |
| `private_ui_content` | Notifications, messages, contacts, account screens, photos, or other non-public device content |
| `local_environment` | User-specific absolute paths, usernames, shell history, environment values, process details, or local configuration |
| `raw_evidence` | Screenshots, recordings, logs, dumps, diagnostics, test URIs, or identifiers collected during verification |
| `release_artifact` | APKs, checksums, signing sidecars, generated bundles, or credentials that do not belong in source history |

Public project names, package identifiers, source paths, and release metadata
may be legitimate when already intentionally public and necessary to the
change. Confirm necessity from repository sources; do not create a list of
private exceptions.

## Audit surfaces

Inspect all relevant surfaces, not only the final file diff:

1. Issue titles, bodies, edits, comments, and attachments.
2. Pull request titles, bodies, reviews, comments, and rendered diffs.
3. Tracked, staged, untracked, generated, and ignored files.
4. Base-to-head diffs, every reachable commit, commit messages, and author
   metadata.
5. Remote branch trees and any files reachable from the proposed merge.
6. Test reports, terminal excerpts, screenshots, APKs, checksums, and sidecars.
7. Examples, fixtures, documentation, skill metadata, and copied command text.

Verify that `artifacts/` remains ignored and untracked. The presence of a file
under an ignored directory is not permission to quote or publish its content.

## Procedure

1. List the exact files, commits, and GitHub text that will become public.
2. Confirm each item is necessary for the Issue and expected by the repository.
3. Review new proper nouns and identifiers semantically. Do not rely on a
   scanner to distinguish a private name from an ordinary word.
4. Run credential and identifier pattern scans without printing matching
   values. Keep any task-local comparison value ephemeral and out of committed
   files, shell transcripts, and reports.
5. Confirm generated and ignored evidence is absent from the index, commits,
   remote branch tree, pull request assets, and release assets unless a
   separate approved workflow explicitly requires an artifact.
6. Inspect the remote Issue and pull request after creation; local drafts do
   not prove the rendered public result is safe.
7. Resolve every unexplained finding before merge. Stop when the public surface
   cannot be inspected or a value cannot be classified confidently.

## Report format

Report a clean audit by category without reproducing the searched values:

```text
public_text: clear
tracked_content: clear
commit_history: clear
remote_branch: clear
generated_evidence: ignored-and-untracked
semantic_identifier_review: clear
```

Report a finding with redaction:

```text
privacy finding: <CATEGORY>
surface: <PUBLIC_SURFACE_OR_FILE>
matched value: [redacted]
action: stop before publication or merge
```

Do not publish a count, excerpt, hash, encoding, or transformed form when it
could help reconstruct the private value.
