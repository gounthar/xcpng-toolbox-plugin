# xcpng-toolbox

A JetBrains Toolbox remote-development provider for XCP-ng, so a VM on a pool shows up as a
development environment you can open an IDE against.

**Unofficial, and not a Vates project.** This is a personal project that talks to XCP-ng and Xen
Orchestra from the outside, using their public REST API. It is not built, endorsed or supported by
Vates, and the name is used only to say what it connects to. It ships no Vates artwork: the icon is
an original mark, deliberately not the XCP-ng logo. Bugs here are mine, not theirs; report them on
this repository rather than to XCP-ng.

## Status

Early, and specific about what has actually been done rather than what should work.

**The whole chain is proven end to end**, on a real pool, by clicking it: list a pool, start a
VM, connect to it, and have an IDE run on it. The evidence for the last two links, because they
are the ones that used to be missing:

- **A connect that succeeds.** 2026-08-19 22:37, against a Debian 13 guest at an address Xen
  Orchestra reported. Nine seconds from click to `Successfully connected to environment`, key
  authentication, no password prompt. The address came from the pool rather than from a manual
  override, so the resolution path was exercised rather than bypassed.
- **An IDE actually running there.** 2026-08-20, same VM: the backend deployed over SSH, an IDE
  opened, it downloaded a JDK by itself, and a class compiled. The `.class` file on the guest is
  what says so, rather than a screenshot.

**Works, driven through the Toolbox UI against a real pool:** the VMs on a pool are listed as
environments, with power state and a state-dependent action menu. Start, resume, unpause, clean
shutdown, force shutdown, take snapshot and revert to a snapshot all run against Xen Orchestra's
REST API and have been exercised by clicking them.

**Rows follow the pool without being asked.** The plugin subscribes to Xen Orchestra's
server-sent events stream, so a VM started or stopped from XO's own web interface shows up here
in about a second (1.05 s, measured three times) rather than on the next look. Events are a
trigger and not a source of truth: a frame says something changed, and a REST read says what it
now is. One caveat that is a property of Toolbox rather than of this plugin: its window is a tray
popup that hides itself when it loses focus, and the stream runs only while the window is on
screen.

**Configuration.** A settings form for the pool URL, the token and a pool-wide default SSH
username, with a Test connection button that names what actually went wrong. A per-VM page for a
username, an address override and a port. The token goes to the OS keychain, not to a file.

**Tests: 125, no failures.** They run on every pull request and every push to main. Read what they are worth
honestly: they cover parsing, message wording, settings and the refusal logic, and they cannot
see anything Toolbox renders. This project's most repeated failure is a change that compiles,
passes everything, and is wrong on screen.

### What it will refuse to do, and why that is the design

The plugin will not hand Toolbox an endpoint it does not believe in. It refuses when the VM is
halted, when it is running but reports no address, and when there is an address but no username
is known anywhere. Each refusal names which case it is.

That is the constraint the whole design bends around. **Xen Orchestra reports a VM last known
address whether or not it is running**: on the lab pool, four of six halted VMs served one while
only one of four running VMs did. A stale address is worse than none, because it looks usable and
an IDE will dial it. A guest with no XCP-ng agent reports nothing, ever, and neither waiting nor
subscribing changes that: the events payload carries no resolved address at all. Separately,
**nothing in Xen Orchestra records an SSH username**, so that can only ever be configuration.

Hence the per-VM override, which makes any VM reachable by hand today.

### Things worth knowing before you point an IDE at a VM

- **JetBrains asks for 4 cores and 8 GB, and it is advisory rather than enforced.** The connect
  above succeeded on 2 vCPU and 4 GiB. What degrades is the editing: indexing a two-file project
  took resident memory from 1.29 GB to 3.09 GB and put the VM into swap.
- **Disk is the binding constraint, and it is roughly double what you expect.** One IDE plus one
  JDK is about 6.2 GB, and Toolbox never deletes the installer archives it has already extracted,
  so the peak during an install is close to twice the installed size and does not come back down.
- **Nothing is baked into the image.** The backend arrives over SSH at connect time, so a template
  needs a supported distribution, an SSH user, disk headroom and whatever runtime you work in. It
  does not need an IDE. It does need `git` if you intend to clone anything: the IDE fetches a JDK
  by itself but not a git, and it reports the absence as a spinner that never stops.
- **The token is the authority.** An admin token is the whole pool, for every developer holding
  it. See [SECURITY.md](SECURITY.md), including how to scope one properly server-side and the two
  ways a selector will surprise you.

## Why Toolbox and not Gateway

JetBrains Gateway is the obvious target and it is the wrong one. Gateway's connector API
(`gatewayConnector`, `gatewayConnectionProvider`) is undocumented: the SDK pages 404 and
`JetBrains/intellij-sdk-docs` has no reference to it. JetBrains publishes a migration guide
away from Gateway to the Toolbox App, and the vendors who built Gateway connectors have all
shipped Toolbox successors. Coder's Gateway README now says future work goes to their Toolbox
plugin.

The Toolbox remote-dev API is documented, versioned, and published as ordinary Maven
artifacts.

To be accurate about what that buys: Toolbox does not hand you VM lifecycle either. There is
no start/stop in the API, and `RemoteEnvironmentAbility` offers only `CAN_RENAME`,
`ALWAYS_CONNECTED` and `SKIP_DELETE_CONFIRMATION`. What it gives you is a documented place to
hang your own control-plane calls: a state flow whose vocabulary already fits a VM
(`Hibernated`, `Activating`, `Restarting`, `FailedToStart`, `Inactive`) and an `actionsList`
Toolbox renders for you.

## Building and installing

```
./gradlew build           # compile, test, jar
./gradlew installPlugin   # copy it where Toolbox actually reads plugins
./gradlew packagePlugin   # the zip a release ships
```

Quit Toolbox before installing. Two things that look like failures and are not: `installPlugin`
is a `Sync`, so a change that leaves the bytecode identical (a comment, some KDoc) skips the copy
and the installed file keeps its old timestamp; and Toolbox rewrites its own `settings.json` on
exit, so an edit made while it runs is silently discarded.


JDK 21, and pinned rather than preferred: `gradle/gradle-daemon-jvm.properties` sets
`toolchainVersion=21` because a newer JDK fails while compiling the *build script*, with an
error naming no file and no task.

The Kotlin version is bounded on **both** sides, which is why it is not a routine bump. The
compiler must be new enough to read the binary metadata in the Toolbox API jars, and
`kotlin-stdlib` is supplied by Toolbox at runtime, so compiling above what the installed app
bundles is a `NoSuchMethodError` that CI cannot see: CI only compiles. The same applies to
`kotlinx-coroutines` and `kotlinx-serialization`, which are `compileOnly` for the same reason.
The working is in `gradle/libs.versions.toml`, next to the values.

### Hooks, and two rules this repository enforces mechanically

`./gradlew build` points `core.hooksPath` at `.githooks` for this repository, prints that it did,
and stays quiet afterwards. It sets the **local** value, so a global hooks directory of your own
keeps working everywhere else. Opt out with `-PskipGitHooks`.

The hooks exist for two rules that will fail your build or your pull request rather than being
caught in review: **nothing tracked here names the tools used to write it**, and **no em dashes in
anything tracked**. Both are explained, with the incidents that produced them, in
[CONTRIBUTING.md](CONTRIBUTING.md). Check your own work with:

```
.github/scripts/check-tooling-references.sh [base head]
```

Exit 0 clean, 1 a violation, 2 the check could not run. Read the 2, because a check that
could not run must not look like one that passed.

## Related

- `xcpng-cloud-plugin`: the Jenkins cloud plugin for the same pools. Its
  `io.jenkins.plugins.xcpng.client` package is the JVM prior art for talking to a pool.
- `clawk`: a sandbox tool with an XCP-ng backend in Go.

## Contributing, security, licence

- [CONTRIBUTING.md](CONTRIBUTING.md) for how to build it, what the enforced rules are, and the
  one check CI structurally cannot do.
- [SECURITY.md](SECURITY.md) for what the plugin holds, what the token can do, and how to report
  something privately.
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md), the Contributor Covenant 2.1 unmodified.
- [MIT](LICENSE). Coder's Toolbox plugin, which is the worked example this one was written by
  reading, is MIT too.
