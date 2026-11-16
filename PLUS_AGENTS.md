# Metrolist+ fork rules

This checkout is Metrolist+, a personal fork of MetrolistGroup/Metrolist. These rules come before `AGENTS.md`, which is upstream's guide and still applies to code style and PR content.

## Remotes

- `origin` is the fork, `github.com/uzuki-P/Metrolist`. All work goes here.
- `upstream` is MetrolistGroup/Metrolist. It is fetch-only: its push URL is `DISABLED` and must stay that way.
- `v14` is the fork's main branch. It carries every Metrolist+ change on top of upstream.

## Pushing

- Never push, force push, or open, update or merge a PR unless the user asks for that action in the current conversation. Approval covers only the push or PR it was given for.
- Never push to `upstream` or change its push URL.
- Commit locally when the work is done, then report what is ready to push and wait.

## Syncing upstream

- Merge `upstream/main` into `v14`. Do not rebase `v14`, because it is already pushed.
- Upstream workflows under `.github/workflows/` stay deleted; the fork's CI is `ci(plus)`.
- After the merge, compile both apps one at a time, because building both in one Gradle run can run out of memory: `:desktopApp:compileKotlinJvm`, then `:androidApp:compileDebugKotlin`. Then run `just test`.

## Clean PRs

A PR contains only the change it is for, never the rest of the fork.

1. Pick the target first: `MetrolistGroup/Metrolist` `main` for an upstream contribution, or the fork's `v14` for a fork-only change.
2. Branch from that target, not from `v14` or another feature branch:
   - upstream: `git fetch upstream && git switch -c pr/<topic> upstream/main`
   - fork: `git fetch origin && git switch -c <type>/<topic> origin/v14`
3. Bring in only the commits for the change (`git cherry-pick <sha>...`), or reimplement it on the new branch when the fork commits mix in other work.
4. Leave fork-only material out of upstream PRs: the Metrolist+ rebrand, app ID and icon, `ci(plus)` workflows, `justfile`, `scripts/stage-artifact`, `scripts/ci-*`, `PLUS_NOTICES.md`, `PLUS_AGENTS.md` and the pointer at the top of `AGENTS.md`.
5. Check the diff against the base before publishing: `git diff --stat <base>...HEAD` and `git log --oneline <base>..HEAD` must list only this change.
6. Build and test on that branch, then stop and ask before pushing.
7. When the user approves, push the branch to `origin` and open the PR from the fork:
   `gh pr create --repo MetrolistGroup/Metrolist --base main --head uzuki-P:pr/<topic>` (upstream), or `--repo uzuki-P/Metrolist --base v14` (fork).
