# Install Beacon

## Install the app

Download the latest `Beacon-*.apk` from [GitHub Releases](https://github.com/xploit9/beacon/releases/latest), open it on your phone, paste your Hermes link and sign in with Hermes. Signing in links the phone. When the Beacon plugin is not on your Hermes yet, the setup wizard offers to install it ("Add the Beacon plugin to Hermes"): Hermes downloads it from GitHub and turns it on, and the wizard shows each step as it happens. Hermes loads a new plugin without restarting the dashboard; it asks for a Hermes restart only when it says one is needed. Then the wizard asks for your voice key, the permissions calls need, and Keep connected. If the install fails, the wizard says why and offers only what can help: Try again, Sign in again, Skip for now, or the PC steps below ("Set up from a computer").

The link is your dashboard's HTTPS address, such as `https://<your-pc>.<your-tailnet>.ts.net`. The dashboard's **Beacon** tab also shows it once Beacon is installed.

## No HTTPS address yet?

Paste this prompt into your AI agent (Codex, Claude Code, or Hermes itself) on the PC that runs Hermes:

```text
Get my PC ready for the Beacon app: put my Hermes dashboard on a private HTTPS address with Tailscale. Do each step yourself and ask me only when a step needs me. Do not install the Beacon plugin; the Beacon app does that on its own.

1. Install Tailscale on this PC unless `tailscale version` already works.
   - Windows: `winget install -e --id Tailscale.Tailscale` (or https://tailscale.com/download/windows). If `tailscale` is not found afterwards, use `C:\Program Files\Tailscale\tailscale.exe`.
   - macOS: install the Tailscale app from https://tailscale.com/download/mac.
   - Linux: `curl -fsSL https://tailscale.com/install.sh | sh`, then `sudo tailscale up`.
2. Sign this PC in to my Tailscale account (Windows and macOS: open the Tailscale app; Linux: the link `tailscale up` prints). Wait until `tailscale status` shows this PC connected.
3. Ask me to open https://login.tailscale.com/admin/dns, make sure MagicDNS is on, and choose Enable HTTPS under HTTPS Certificates. Wait until I say it is done.
4. Find the port my Hermes dashboard listens on (9119 unless I changed it) and check that http://127.0.0.1:<port>/api/status answers. If the dashboard is not running, tell me and stop.
5. Run `tailscale serve --bg <port>`, then `tailscale serve status` to read the HTTPS address. It looks like https://<your-pc>.<your-tailnet>.ts.net
6. Save it with `hermes config set dashboard.public_url <that address>` (use `hermes -p <profile> config set ...` if my dashboard runs under a named profile).
7. Check that `curl -fsS <that address>/api/status` answers with Hermes' status. Fix anything that fails before you go on, and ask me before you restart the dashboard.
8. Tell me to install Tailscale on my phone (Google Play), sign in with the same account and leave it connected.

Never ask for or print passwords, tokens, Tailscale keys or API keys, and never modify Hermes core. When every step has passed, finish with this message, with the real address filled in:

You're ready. Install the Beacon app on your phone, paste this link, and the app installs the Beacon plugin and walks you through the rest: <address>
```

## Alternative: set up from the PC

Use this when you want the Beacon plugin on your Hermes before the app, or when the wizard cannot install it.

### Run the installer

On the PC that runs Hermes, download [`beacon_install.py`](https://raw.githubusercontent.com/xploit9/beacon/main/hermes-plugin/beacon/install/beacon_install.py) and run it with Python 3.10 or newer:

```sh
python beacon_install.py
```

The installer selects the Hermes home and profile (it asks when more than one could be yours), installs or updates Beacon with Hermes' own installer, restarts the dashboard if you say yes, and prints the link to paste into Beacon. When it can't ask you directly, it names the flag to rerun with: `--home`, `--profile`, `--yes` (restart), `--tailscale` (set up an HTTPS address with Tailscale). `--dry-run` shows the plan and changes nothing.

Or give your agent this prompt:

> Install Beacon on my Hermes: download https://raw.githubusercontent.com/xploit9/beacon/main/hermes-plugin/beacon/install/beacon_install.py and run it with Python. Whenever it stops to ask something, it names a flag — ask me, then rerun it with that flag. Show me the link it prints at the end. Never print or store passwords, tokens or API keys, and never modify Hermes core.

### Manual steps

1. Choose the Hermes home and profile that run your dashboard; ask if more than one could be it.
2. Install with `hermes -p <profile> plugins install xploit9/beacon/hermes-plugin/beacon --enable`, or update with `hermes -p <profile> plugins update beacon`. Let Hermes' security scan and prompts run unchanged.
3. Restart the selected dashboard through its actual scheduled task, service or verified manual process, preserving its home, profile and port. Use native idle and lifecycle safeguards before stopping it; obtain approval if the owner has not already authorized the restart. The broad `dashboard --stop` command can stop other dashboards on the machine.
4. Give the dashboard a public HTTPS address; that address is the link you paste into Beacon. No address yet? Use the Tailscale prompt above, or run `tailscale serve --bg 9119` (your dashboard's port) and save the HTTPS address it shows with `hermes -p <profile> config set dashboard.public_url https://<your machine's Tailscale name>`. `python beacon_install.py --tailscale` does both.

Then install the app, paste the link and sign in with Hermes.

## Background alerts

Enable **Keep connected** in Notifications and allow Android notifications and background use. This uses Android's foreground service and a renewable, time-bounded CPU wake lock while online, so the connection can receive alerts with the screen asleep. It does not keep the screen on. Turning Keep connected off or losing the network releases that protection; keeping a live connection uses battery.

The connection indicator requires a recent valid response and expires when responses stop. Update checks retain their normal cadence; an already verified update whose notification was blocked can be announced after notifications are allowed without waiting for another network check. Pending installer confirmations remain protected. Android's notification permission, channel settings and force-stop rules remain authoritative.

Voice startup also depends on the dashboard loading the installed plugin. Its authenticated `/api/plugins/beacon/mobile/plugin/version` response reports `running` (the dashboard), `gateway` (the version the gateway loaded, empty until it has loaded the plugin) and `installed`; a `running` that differs from `installed` needs a dashboard restart, even after the gateway restarts. An older loaded voice implementation can otherwise keep timing out after the update. A `502` from a saved connection address also requires checking that address's configured dashboard port is actually listening.

## Coming from Hermes Relay?

Beacon replaces the Hermes Relay plugin and does not remove it for you. Before installing, back up `hermes-relay` outside the `plugins` folder, check the copy, then use Hermes' own commands to remove that plugin and only the scheduled jobs it created. Write down each job (name and schedule) before you remove it, and if you can't tell whose a job is, leave it. Leave your conversations, memories, other jobs and credentials alone. Skip this if you never used Hermes Relay.
