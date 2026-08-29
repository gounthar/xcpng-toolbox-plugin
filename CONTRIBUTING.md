# Contributing

Thanks for looking. This is a personal project rather than a product, so the bar here is
"does it work and can the next person tell why", not process.

Read the second paragraph of the [README](README.md) first if you have not: this is not a
Vates project and bugs belong on this repository rather than with XCP-ng.

Participation is covered by the [Code of Conduct](CODE_OF_CONDUCT.md).

## The two rules that are enforced mechanically

Both will fail your build or your pull request rather than being caught in review, so they
are worth knowing before you write anything.

### Nothing tracked here names the tools used to write it

Not code, not comments, not commit messages, not pull request titles or bodies. Four things
enforce it and only the last cannot be skipped:

| Where | What it does |
|---|---|
| `.githooks/prepare-commit-msg` | silently strips an attribution trailer before you see it |
| `.githooks/commit-msg` | refuses prose that names a tool |
| CI, on a pull request | scans tracked files, the commits, and the title and body |
| CI, on a push to `main` | scans the pushed range, catching a squashed merge message |

The last row is not hypothetical. Seven attribution trailers reached the default branch
through a squash, whose message no hook ever sees, and clearing them meant rewriting history.

Check your own work with:

```
.github/scripts/check-tooling-references.sh [base head]
```

Exit 0 is clean, 1 is a violation, 2 means the check could not run. Read the 2: a check that
could not run must never look like one that passed.

### No em dashes in anything tracked

`NoEmDashesTest` fails the local build with a file and a line number. It asks git for its file
list, so the scope is exactly what this repository publishes.

The rule came from reading one aloud. The certificate branch of the Test connection popup was
transcribed off the screen on 2026-08-21, and the dash arrived mid-sentence as the spoken words
"em dash", in a message whose entire job is telling somebody how to fix their connection. Use
the punctuation the clause actually wants: a colon before an explanation, a full stop between
two sentences, parentheses around an aside.

## Building

```
./gradlew build
```

JDK 21, pinned in `gradle/gradle-daemon-jvm.properties` rather than preferred. A newer JDK
fails while compiling the *build script*, with an `IllegalArgumentException` naming no file, no
task and no cause.

`./gradlew build` also points `core.hooksPath` at `.githooks` for this repository and says so
once. It sets the local value, so a global hooks directory of your own keeps working elsewhere.
Opt out with `-PskipGitHooks`.

Other tasks worth knowing: `./gradlew installPlugin` copies the jar and its manifest into the
Toolbox plugin directory, and `./gradlew packagePlugin` builds the zip a release ships.

## The one thing CI structurally cannot check

**A change can compile, pass every test, and be wrong in the Toolbox UI.** That is not a
worry, it is this project's most repeated failure. Pull request #6 renamed the provider, was
reviewed, merged and recorded as done in two places, and the list still showed the old name on
the next launch, because the plugin's name and the provider's name are two different strings.
Everything about it was true except the part that mattered.

So if your change touches anything Toolbox renders, install it and look at it:

```
./gradlew installPlugin
```

Quit Toolbox first. And check the *contents* of the installed jar rather than its timestamp:
`installPlugin` is a `Sync` and skips a byte-identical copy, so a comment-only change leaves the
old mtime and looks like a failed install.

Two related traps, for anything ephemeral. Popups are never logged, so if the only output of
your test is a modal, transcribe the wording **while it is on screen**. And the Toolbox window
is a tray popup that hides itself when it loses focus, which stops the event stream, so a state
change made while you are typing in a terminal produces nothing at all and looks exactly like a
dead stream.

## Three version numbers that are ceilings, not floors

`kotlin`, `kotlinx-coroutines` and `kotlinx-serialization` in `gradle/libs.versions.toml` are
supplied by Toolbox at runtime and are `compileOnly` here. Compiling above what the installed
app bundles is a `NoSuchMethodError` inside Toolbox that **CI cannot see, because CI only
compiles**. `gradle/toolbox-runtime.yaml` records which Toolbox build the current values were
matched against, and a scheduled job opens a pull request when the published build moves.

Do not bump them because a tool suggested it. Bumping one means installing that Toolbox,
launching it, and reading the log for the line that exercises all three at once.

## Pull requests

- Conventional commits. One logical change per commit, branch per change, never commit to `main`.
- Put the reasoning in the commit body rather than only in the pull request description. The
  description is read once; `git log` and `git blame` are read for years.
- Say what you actually verified and how, and say plainly what you did not. "Should work" is not
  a verification, and neither is a green tick from something that never ran.
- End every text file with a newline.

## Licensing

This project is under the MIT license (see `LICENSE`). By contributing, you agree that your
contributions are licensed under the same terms: inbound is the same as outbound. No separate
agreement is needed.

## Filing an issue

Templates are under `.github/ISSUE_TEMPLATE`. The thing most worth including is the log:

```
%LOCALAPPDATA%\JetBrains\Toolbox\logs\toolbox.latest.log      (Windows)
~/.local/share/JetBrains/Toolbox/logs/toolbox.latest.log      (Linux)
```

Search it for `XCP-ng:`, which prefixes every line this plugin writes. **Redact your pool
address and anything token-shaped before pasting.**
