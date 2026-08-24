# Security

This is a personal project maintained by one person in his own time. There is no security team
and no response-time commitment. What there is instead is an honest account of what the plugin
holds, what it trusts, and where the sharp edges are.

## Reporting something

Open a [private security advisory](https://github.com/gounthar/xcpng-toolbox-plugin/security/advisories/new)
rather than a public issue. If that is not available to you, open an ordinary issue saying only
that you have something to report, with no detail, and you will be contacted.

**Do not report anything about XCP-ng or Xen Orchestra themselves here.** This plugin is not a
Vates project and has no visibility into theirs. Those go to Vates through their own channels.

## What the plugin holds

**One credential: a Xen Orchestra REST API token.** It is written to the OS keychain through
Toolbox's `PluginSecretStore`, which is `windows-dpapi` on Windows. The settings page never
pre-fills the field with the stored value, because reading a secret back out of the keychain to
paint it into a form puts it on screen for no reason; a blank field means "keep the stored one"
rather than "there is no token".

**There is one plaintext path and it is deliberate.** A token seeded by hand into the plugin's
`settings.json` before the settings page existed is still read, because `settings.json` is
plaintext on disk and the alternative was silently ignoring somebody's working configuration.
It is migrated on sight: writing anything through the settings page moves it to the keychain and
blanks the file copy. A token entered through the UI never touches the file at all.

**No SSH credential is handled anywhere.** The plugin resolves a username, an address and a port
and hands them to Toolbox; Toolbox's own SSH deployment session does the rest, using the system
SSH configuration and agent. Nothing here reads a private key, prompts for a passphrase, or
stores a password.

## What the token can do, which is the part worth reading

**The token holds the authority. The plugin holds a display preference.** Anyone who can read
the keychain entry has whatever Xen Orchestra grants that token, through any XO client, whether
or not this plugin is involved. Filtering what appears in the environment list would change what
is on screen and nothing about what is permitted.

That matters because of how the token is usually made. On an appliance where scoping is not
available, the practical answer is an admin token, and **an admin token is the whole pool**:
every VM listed, and power-cycling authority over all of them. That is not what "give somebody
access to their dev box" sounds like, so it is said here plainly.

**How to tell which appliance you have.** Scoping needs a per-developer token, and the
non-admin REST surface is licence-gated. On an appliance below the threshold every non-admin data
route answers `403` with `featureCode: RBAC` and a body naming `currentPlan` and `minPlan`, so a
scoped token cannot reach the route at all, scoped or not. Ask for any VM as the non-admin user
and read the body:

```
curl -sk -u '<user>:<password>' https://<xoa>/rest/v0/vms
```

A list means scoping is available to you. A `403` naming `featureCode: RBAC` means it is not, a
shared token is the only working design on that appliance, and the paragraph above is the one that
applies.

Xen Orchestra can scope this properly, server-side, and it is the better answer where it is
available: an ACL V2 privilege carries an optional `selector`, and both the VM list and the power
verbs respect it. Four measured warnings if you set one up:

- **`tags:` and `id:` selectors match by case-insensitive substring, not exactly.**
  `tags:dev` also grants `dev-prod`, `development` and `my-dev-box`. `id:a` grants any VM whose
  uuid contains an `a`, which on a normal pool is nearly all of them.
- **An over-broad `deny` fails silently in the dangerous direction.** The collection route answers
  `200 []`, never an error, so a scope denied by a stray substring is indistinguishable from an
  unconfigured pool.
- **`vm-snapshot` is a separate privilege resource, and forgetting it empties the revert picker.**
  Granting every `vm` privilege still leaves `GET /vm-snapshots` returning nothing, including
  snapshots that user took themselves. Grant `read on vm-snapshot` with the same selector. Note
  that reverting is `revert-snapshot` on the *vm*, not an action on the snapshot.
- **Inherited tags are a snapshot-time copy, not a live link.** A snapshot takes its parent VM's
  tags at the moment it is taken and does not follow the VM afterwards, so somebody added to a
  tag-based scope after their snapshots exist sees the VM but not its history. Nothing this plugin
  can fix from its side.

## Accept a self-signed certificate

The settings page has a checkbox for it, and XOA ships a self-signed certificate, so on a lab
pool it is usually ticked. Be clear about what it costs: with it on, certificate validation is
disabled for the pool connection, so the connection is encrypted but not authenticated and an
attacker positioned on the path can present their own certificate and read the token.

It is off by default and the failure message that tells you to tick it names the certificate
error verbatim, so you can tell an ordinary first connection to a self-signed appliance from
something worth stopping for. On a pool with a certificate your machine trusts, leave it off.

## Scope

In scope: this plugin's handling of the token, the endpoint it resolves and hands to Toolbox,
and what it writes to disk or to the log.

Out of scope: Xen Orchestra, XCP-ng, JetBrains Toolbox, and anything reachable only by someone
who already has the machine or the keychain.
