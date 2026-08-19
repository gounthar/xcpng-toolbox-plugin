# xcpng-toolbox

A JetBrains Toolbox remote-development provider for XCP-ng, so a VM on a pool shows up as a
development environment you can open an IDE against.

## Status

Early, and honest about which half works.

**Works, driven through the Toolbox UI against a real pool:** the VMs on a pool are listed as
environments, with power state and a state-dependent action menu. Start, resume, unpause, clean
shutdown, force shutdown, take snapshot and revert to a snapshot all run against Xen Orchestra's
REST API and have been exercised by clicking them.

**Written but not yet verified:** connecting. There is now a settings form for the pool URL and
token, a pool-wide default SSH username, and a per-VM page for a username, an address override and
a port; a VM whose address and username both resolve hands Toolbox a real SSH endpoint. **No IDE
has actually been opened against a VM yet**, so treat "connecting works" as unproven until it has.

The reason an address override exists is worth stating, because it is the constraint the whole
design bends around. Xen Orchestra reports a VM's *last known* address whether or not it is
running: on the lab pool, four halted VMs served an address while only one of four running VMs
did — and that one was the appliance itself. A guest with no XCP-ng agent reports nothing, ever,
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

JDK 21. Kotlin 2.3.10 is required rather than preferred; see the comment in
`gradle/libs.versions.toml`.

## Related

- `xcpng-cloud-plugin` — the Jenkins cloud plugin for the same pools. Its
  `io.jenkins.plugins.xcpng.client` package is the JVM prior art for talking to a pool.
- `clawk` — a sandbox tool with an XCP-ng backend in Go.
