# Contributing

Henyo is maintained as a personal experimental project. Bug reports and
technical discussion are welcome, but feature requests and pull requests may
be declined when they do not match the maintainer's direction or available
time.

## Workflow

1. Open or reference a GitHub Issue that describes the goal, scope, acceptance
   criteria, and verification plan.
2. Create a focused branch named `<issue-number>-<short-description>`.
3. Keep commits scoped and avoid including device captures, UI text, tokens,
   credentials, local paths, or other private environment data.
4. Run `gradle check` and any device verification relevant to the change.
5. Open a pull request that links the Issue and records the checks performed.

Security vulnerabilities should not be filed as public Issues. Use GitHub's
private vulnerability reporting flow when it is available for this repository.
