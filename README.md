<img src="https://raw.githubusercontent.com/House-elves/elf-desk/main/docs/brand/mark.svg" width="72" alt="House Elves logo: a checkmark that ends in an elf's hat">

# PR wall

A [House Elves](https://github.com/House-elves) elf that puts a repo's open PRs on a wall, each with what TypeSafe's
[jev](https://typesafe.ai) says about its tests. `quarkusio/quarkus` is the default.

jev is a decision model: it answers typed questions with probabilities instead of prose. For each PR it's asked:

| Question | Answer |
|---|---|
| Does the PR change runtime behaviour? | P(yes) |
| How much of the changed code do its changed tests run? | all, some, none, no runtime change, no test changes, undetermined |
| Do those tests assert the outcome? | all, some, none, no runtime change, no test changes, no changed code reached, undetermined |

Whether the PR changes tests at all isn't asked: it's read from the file paths and given to jev as a fact.

The answers are shown as jev gave them. They are never combined into a score, and nothing on the wall says a PR is
good or bad. You can sort by one answer, filter by label or text, and open every option's probability. **Right** and
**Wrong** on each answer save your verdict with the answer it judged, so jev's answers can be measured before
anything is built on them.

- **Reads:** the open PRs of the repo you set, and their diffs, through `gh`.
- **Writes:** each PR's diff, with its list of files and the repo's context, to jev at `api.typesafe.ai`, once per new
  commit on the PR. Nothing to GitHub: it never comments, labels or merges. What it found is saved in
  `~/.config/pr-wall/runs/`, jev's answers per PR in `cache/`, and your verdicts in `feedback/`.

## What jev is sent

The repo's context (its layout and where its tests live, from `contexts/<owner>/<name>.json`, or
`contexts/default.json`), a line with the number of test files, the list of changed files, and the diff. The PR's
title and body aren't sent. Lock files, generated files and binaries are listed but their content is left out. If the
rest is still over **Largest diff sent**, docs lose their content too. A PR that's still too large shows as too large
and isn't cut part way.

The questions are in `questions.json`. Changing them, the repo's context or the jev model asks about every PR again.

Each PR costs a fraction of a cent: jev is $42 per billion input tokens, so a 60,000-character diff is about $0.0006.

## Adding it

Click **+** on the desk and pick PR wall. The TypeSafe API key is optional at setup: without it, **Show what it would send** works, and **Refresh the wall** lists the open PRs without jev's answers, with a button to add the key.

## Running it by hand

    mkdir -p ~/.config/pr-wall && printf 'GITHUB_USER=your-login\nTYPESAFE_API_KEY=...\n' > ~/.config/pr-wall/config
    jbang PrWall.java                                  # what it would send for each PR; sends nothing
    jbang PrWall.java --pr 12345 --print-submission    # the exact request for one PR; sends nothing
    jbang PrWall.java --save                           # asks jev and saves the wall for its tab

## Credits

The wording of the questions and the Quarkus context are adapted from brunobat's
[quarkus-pr-wall](https://github.com/brunobat/quarkus-pr-wall) (Apache-2.0). See [NOTICE](NOTICE).
