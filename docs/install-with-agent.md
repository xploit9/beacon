# Install Beacon with your agent

## Run the installer

On the PC that runs Hermes, download [`beacon_install.py`](https://raw.githubusercontent.com/xploit9/beacon/main/hermes-plugin/beacon/install/beacon_install.py) and run it with Python 3.10 or newer:

```sh
python beacon_install.py
```

The installer selects the Hermes home and profile (it asks when more than one could be yours), installs or updates Beacon with Hermes' own installer, restarts the dashboard if you say yes, and prints the link to paste into Beacon. When it can't ask you directly, it names the flag to rerun with: `--home`, `--profile`, `--yes` (restart), `--tailscale` (set up an HTTPS address with Tailscale). `--dry-run` shows the plan and changes nothing.

## Manual steps

1. Choose the Hermes home and profile that run your dashboard; ask if more than one could be it.
2. Install with `hermes -p <profile> plugins install xploit9/beacon/hermes-plugin/beacon --enable`, or update with `hermes -p <profile> plugins update beacon`. Let Hermes' security scan and prompts run unchanged.
3. Ask before restarting the dashboard, then restart it the way it runs: its scheduled task or service, or `hermes -p <profile> dashboard --stop` then `hermes -p <profile> dashboard`. Start it the same way if it isn't running.
4. Give the dashboard a public HTTPS address; that address is the link you paste into Beacon. No address yet? Tailscale Serve is the easy route: run `tailscale serve --bg 9119` (your dashboard's port), then save the HTTPS address it shows with `hermes -p <profile> config set dashboard.public_url https://<your machine's Tailscale name>`. `python beacon_install.py --tailscale` does both.

Install the latest app from [GitHub Releases](https://github.com/xploit9/beacon/releases/latest), paste the link and sign in with Hermes. Signing in links the phone.

## Coming from Hermes Relay?

Beacon replaces the Hermes Relay plugin and does not remove it for you. Before installing, back up `hermes-relay` outside the `plugins` folder, check the copy, then use Hermes' own commands to remove that plugin and only the scheduled jobs it created. Write down each job (name and schedule) before you remove it, and if you can't tell whose a job is, leave it. Leave your conversations, memories, other jobs and credentials alone. Skip this if you never used Hermes Relay.
