# xcpng-toolbox

A JetBrains Toolbox remote-development provider for XCP-ng, so a VM on a pool shows up as a
development environment you can open an IDE against.

## Status

Early, and honest about which half works.

**Works, driven through the Toolbox UI against a real pool:** the VMs on a pool are listed as
environments, with power state and a state-dependent action menu. Start, resume, unpause, clean
shutdown, force shutdown, take snapshot and revert to a snapshot all run against Xen Orchestra's
REST API and have been exercised by clicking them.

**Does not work:** connecting. Opening an IDE against a VM needs an SSH endpoint, and getting a
trustworthy address is the open design problem — Xen Orchestra reports a VM's last known address
whether or not it is running, so a halted VM can report one and a running VM often reports none.
The plugin refuses to connect rather than handing the IDE an address it invented.

Also missing: any settings UI (configuration is seeded by hand), and any tests.

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

JDK 21. Kotlin 2.3.10 is required rather than preferred; see the comment in
`gradle/libs.versions.toml`.

## Related

- `xcpng-cloud-plugin` — the Jenkins cloud plugin for the same pools. Its
  `io.jenkins.plugins.xcpng.client` package is the JVM prior art for talking to a pool.
- `clawk` — a sandbox tool with an XCP-ng backend in Go.
