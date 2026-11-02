# What's new

What changed for you in each version of PR wall, newest first. The desk shows the parts that are new when it offers
you an update.

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
