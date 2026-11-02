# What's new

What changed for you in each version of PR wall, newest first. The desk shows the parts that are new when it offers
you an update.

## 0.3.0

- New: jev is asked what kind of change each PR is (bugfix, feature, refactor, docs, test-only, build/CI, dependency
  upgrade, revert), judged from the code rather than the title. Filter the wall by it.
- New: jev is asked whether each PR removes, disables or loosens existing tests or assertions. Sort by it.
- Both have Right, Wrong and What's right?, and show in How right jev has been.
- Every PR is asked again on the next refresh, since the questions changed.

## 0.2.0

- New: How right jev has been, at the top of the wall once you've judged some answers. For each question, how often
  jev was right, split by how sure it was, and what it said when it was wrong.
- New: after Wrong, What's right? lets you say which answer is right.
- Fixed: the wall holds the most recently updated PRs, as its setting says, not the newest opened.
- Fixed: raising Largest diff sent asks again about PRs that were too large for the old limit.
- Fixed: answers about PRs that are off the wall for two weeks are removed, even when the repo has more open PRs
  than the wall shows.

## 0.1.3

- Fixed: the tab no longer hangs on loading. It read your saved sort order the wrong way.

## 0.1.2

- New: without a TypeSafe API key, Refresh the wall lists the open PRs instead of failing, and the tab has an Add the
  API key button. The first refresh after setup no longer shows an error.

## 0.1.1

- Fixed: a PR whose diff is too large to read, or comes back empty, is shown as too large or failed, instead of being
  sent to jev as if it changed nothing.

## 0.1.0

- Lists a repo's open PRs (quarkusio/quarkus by default) and asks TypeSafe's jev three questions about each: does it
  change runtime behaviour, do its tests reach the change, and do they assert it. Whether it changes tests at all is
  read from the file paths.
- Each PR is asked once per commit. Changing the questions, the repo's context or the model asks again.
- Large PRs are sent without lock files, generated files and, if needed, docs. A PR still too large says so.
- Sort by one answer, filter by label or text, and open every answer with its probability.
- Right and Wrong on each answer, saved with the answer, so jev's answers can be measured.
