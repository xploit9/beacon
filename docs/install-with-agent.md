# Install Cypher with your agent

This procedure is for an AI agent running on the PC that hosts Hermes.

## Safety

- Never print, copy into notes, or store passwords, OAuth tokens, `GITHUB_TOKEN`, `GH_TOKEN`, dashboard session values, or provider API keys.
- Never modify Hermes core files, add startup hooks, or patch Hermes at runtime.
- Ask the user before restarting a dashboard they are using or changing Tailscale Serve, firewall, bind, or port settings.
- Keep backups and logs outside the Cypher repository. Do not delete the Hermes Relay backup.
- Let Hermes' native installer run its security scan. Ask before approving Python dependency installation or any scan override.

## 1. Confirm Hermes and the target profile

Run `hermes --version` and inspect the existing `HERMES_HOME` and active Hermes profile. If Hermes is not installed or the command is unavailable, politely stop and tell the user that nothing was installed. If more than one profile could be the dashboard's profile, ask which one to use; do not guess.

Use `hermes -p <profile>` for every profile-scoped command. Hermes consumes `-p` / `--profile` before the subcommand, so the profile option goes before `plugins`.

## 2. Install or update the plugin

The repository is private: `xploit9/cypher`. Hermes' native installer accepts owner/repository shorthand, Git URLs, and a repository subdirectory. For the current repository layout, install the `hermes-plugin/cypher` subdirectory:

```sh
hermes -p <profile> plugins install xploit9/cypher/hermes-plugin/cypher --enable
```

If `cypher` is already installed from this same repository, update it with Hermes' native updater instead:

```sh
hermes -p <profile> plugins update cypher
```

Confirm the existing install's recorded source before updating. If it came from another or unclear source, stop and ask before replacing it. Do not use `--force` to overwrite an unknown install.

The installer can use credentials already available to Hermes: `GITHUB_TOKEN` / `GH_TOKEN`, the user's GitHub CLI login (`gh auth token`), or an existing Git credential helper. Hermes supplies credentials only to the Git process and does not put them in the installed clone. If access is denied, ask the user to sign in with `gh auth login` or authorize the existing credential manager; never ask them to paste a token.

Hermes supports a subdirectory in the repository identifier above. Its optional `--ref` accepts a full 40-character commit SHA, not a branch name; omit it to use the repository's default branch.

If an older Hermes installer rejects the subdirectory identifier, use a sparse checkout and install from a local Git URL. Hermes accepts `file://` URLs with a `#subdirectory` fragment:

```sh
git clone --filter=blob:none --sparse https://github.com/xploit9/cypher.git cypher-source
git -C cypher-source sparse-checkout set hermes-plugin/cypher
hermes -p <profile> plugins install file:///absolute/path/to/cypher-source#hermes-plugin/cypher --enable
```

Replace the final URL with the platform's correctly formed `file://` URL for that local checkout (for example, Windows drive paths use `file:///C:/...`). If that Hermes version does not accept the local Git URL, stop and ask rather than guessing another installer flag.

If the target profile has `hermes-relay` enabled, first copy its installed plugin directory to a timestamped private backup outside the `plugins` directory and verify the copy. Then disable Relay in that profile:

```sh
hermes -p <profile> plugins disable hermes-relay
```

Do not delete the backup. Ensure Cypher is enabled in the selected profile:

```sh
hermes -p <profile> plugins enable cypher
```

If Hermes asks to install Python dependencies, explain the packages and wait for the user's approval. An agent running without a terminal prompt then repeats the install command with `--yes-deps`; without it Hermes declines and changes nothing. Do not bypass the native security scan.

## 3. Restart and verify the dashboard

Identify how this dashboard instance is running (foreground process, Windows service/task, systemd, launchd, or container). Ask the user before restarting it. After approval, restart only the selected dashboard instance using its existing platform manager or launch command; do not restart unrelated Hermes services.

The dashboard serves the plugin's phone routes, and it is often a separate process from the gateway: `hermes gateway restart` alone leaves the old plugin running there. After restarting, confirm that the process listening on the dashboard port started after the install.

From the dashboard's authenticated browser session, verify that:

- The dashboard is reachable.
- The **Cypher** tab is visible.
- The owner opens the Cypher tab and, if prompted, taps “Use Cypher with this Hermes account” once.
- The owner-authorized `GET /api/plugins/cypher/connect-info` succeeds and the tab displays its QR.

Do not copy, print, or log the route response. Loopback mode may include a session value in that response. If the route reports that a public HTTPS URL is required, the phone cannot use a loopback or plain-HTTP address. Tailscale Serve over HTTPS is the recommended way to make the dashboard reachable from the phone. Ask before changing network settings; do not enable Serve, open ports, or change firewall/bind settings on your own.

## 4. Finish on the phone

Tell the user to download and install the latest stable `Cypher-<version>.apk` from the private repository's [Releases page](https://github.com/xploit9/cypher/releases), open the dashboard's **Cypher** tab, and scan the QR with the Cypher app. App and plugin automatic updates are on by default in Settings **App updates**; app downloads use an unmetered connection and keep package, version, checksum and signer verification, with a tap-to-install notice whenever Android requires confirmation.

Sign-in uses Hermes' normal OAuth browser flow. If the browser is already signed in, one tap completes it. That single sign-in covers chat, history, plugin pages, calls, and Keep connected. If the dashboard has no accounts and is in loopback mode, scanning the QR signs in immediately.

If restarting or network approval is unavailable, report exactly what remains and wait for the user.
