# xcpng-toolbox

A JetBrains Toolbox remote-development provider for XCP-ng, so a VM on a pool shows up as a
development environment you can open an IDE against.

**Unofficial, and not a Vates project.** This is a personal project that talks to XCP-ng and Xen
Orchestra from the outside, using their public REST API. It is not built, endorsed or supported by
Vates, and the name is used only to say what it connects to. It ships no Vates artwork: the icon is
an original mark, deliberately not the XCP-ng logo. Bugs here are mine, not theirs; report them on
this repository rather than to XCP-ng.

## Status

Early, and honest about which half works.

**Works, driven through the Toolbox UI against a real pool:** the VMs on a pool are listed as
environments, with power state and a state-dependent action menu. Start, resume, unpause, clean
shutdown, force shutdown, take snapshot and revert to a snapshot all run against Xen Orchestra's
REST API and have been exercised by clicking them.

**Also works:** resolving an SSH endpoint and handing it to Toolbox. There is a settings form for
the pool URL and token, a pool-wide default SSH username, and a per-VM page for a username, an
address override and a port. Verified on 2026-08-19 by pointing a VM at an address nothing answers
on and watching the log: `connecting to <uuid> as root@192.168.1.99:9999`, followed by Toolbox's
own SSH deployment session failing with a connection timeout. The username came from the pool
default and the address and port from the override, so all three parts of the resolution are in
that line.

**Still unproven:** a connection that succeeds. Every connect attempted so far was aimed at
something deliberately unreachable, to keep out of a modal that cannot be cancelled, so **no IDE
backend has ever been started on a VM**. What is verified is that the plugin resolves an endpoint
and Toolbox dials it, not that a development environment comes up at the other end.

The reason an address override exists is worth stating, because it is the constraint the whole
design bends around. Xen Orchestra reports a VM's *last known* address whether or not it is
running: on the lab pool, four halted VMs served an address while only one of four running VMs
did, and that one was the appliance itself. A guest with no XCP-ng agent reports nothing, ever,
and no amount of waiting or subscribing changes that. Separately, **nothing in Xen Orchestra
records an SSH username at all**, so that can only ever be configuration. The plugin still refuses
rather than inventing either value.

Also missing: any tests.

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

## Building

```
./gradlew compileKotlin
```

JDK 21, and pinned rather than preferred: `gradle/gradle-daemon-jvm.properties` sets
`toolchainVersion=21` because a newer JDK fails while compiling the *build script*, with an
error naming no file and no task.

The Kotlin version is bounded on **both** sides, which is why it is not a routine bump. The
compiler must be new enough to read the binary metadata in the Toolbox API jars, and
`kotlin-stdlib` is supplied by Toolbox at runtime, so compiling above what the installed app
bundles is a `NoSuchMethodError` that CI cannot see: CI only compiles. The same applies to
`kotlinx-coroutines` and `kotlinx-serialization`, which are `compileOnly` for the same reason.
The working is in `gradle/libs.versions.toml`, next to the values.

### Git hooks

`./gradlew build` points `core.hooksPath` at `.githooks` for this repository, prints that it did,
and stays quiet afterwards. It sets the **local** value, so a global hooks directory of your own
keeps working everywhere else; the note it prints says when it has overridden one. Opt out with
`-PskipGitHooks`, or set it yourself:

```
git config core.hooksPath .githooks
```

Nothing tracked in this repository names the tools used to write it: not code, comments, commit
messages, pull request titles or bodies. Four things enforce that, and only the last cannot be
skipped:

| Where | What it does |
|---|---|
| `prepare-commit-msg` | silently strips an attribution trailer before you see it |
| `commit-msg` | refuses prose that names a tool, which is the case worth a second thought |
| CI, on a pull request | scans tracked files, the commits, and the title and body |
| CI, on a push to `main` | scans the pushed range, catching a squashed merge message |

The last row is not hypothetical: seven attribution trailers reached the default branch through a
squash, whose message no hook ever sees. Run the check yourself with:

```
.github/scripts/check-tooling-references.sh [base head]
```

Exit 0 clean, 1 a violation, 2 the check could not run. Read the 2, because a check that
could not run must not look like one that passed.

## Related

- `xcpng-cloud-plugin`: the Jenkins cloud plugin for the same pools. Its
  `io.jenkins.plugins.xcpng.client` package is the JVM prior art for talking to a pool.
- `clawk`: a sandbox tool with an XCP-ng backend in Go.
