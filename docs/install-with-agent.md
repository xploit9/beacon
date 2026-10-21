# Install Beacon with your agent

On the PC that runs Hermes, download `hermes-plugin/beacon/install/beacon_install.py` from the public [Beacon repository](https://github.com/xploit9/beacon) and run it with Python 3.10 or newer:

```sh
python beacon_install.py
```

The installer selects the Hermes home and profile, verifies a backup of the retired `cypher` plugin before removal, removes only cron jobs whose prompt contains `CYPHER_PROACTIVE_JOB`, then installs or updates Beacon. It asks before restarting the dashboard or configuring Tailscale Serve, and prints the pairing block only when Hermes has a public HTTPS dashboard URL. It does not print or store passwords, tokens, or API keys, and does not modify Hermes core.

## Manual fallback

1. Select the Hermes home and profile with `hermes profile list`; do not guess if more than one could own the dashboard.
2. Back up `<profile-home>/plugins/cypher` and `<profile-home>/plugin-data/cypher` under `<profile-home>/backups/beacon-install-<UTC stamp>/`. Verify file counts and sizes, and use SQLite's backup API for `*.sqlite`, before removal.
3. Inspect `<profile-home>/cron/jobs.json`; remove only jobs whose `prompt` contains `CYPHER_PROACTIVE_JOB` with `hermes -p <profile> cron remove <id>`, then run `hermes -p <profile> plugins remove cypher`.
4. Install with `hermes -p <profile> plugins install xploit9/beacon/hermes-plugin/beacon --enable`, or update an existing Beacon with `hermes -p <profile> plugins update beacon`. Let Hermes' native security scan and prompts run unchanged.
5. Ask before restarting with `hermes -p <profile> dashboard --stop` and `hermes -p <profile> dashboard --no-open`. Pair with `hermes -p <profile> beacon pair --name "My phone"` after a public HTTPS dashboard URL is configured.

Install the latest app from [GitHub Releases](https://github.com/xploit9/beacon/releases/latest) and enter the address and code from the pairing block.
