# Install Beacon with your agent

On the PC that runs Hermes, download [`beacon_install.py`](https://raw.githubusercontent.com/xploit9/beacon/main/hermes-plugin/beacon/install/beacon_install.py) and run it with Python 3.10 or newer:

```sh
python beacon_install.py
```

The installer selects the Hermes home and profile (it asks when more than one could be yours), installs or updates Beacon with Hermes' own installer, restarts the dashboard if you say yes, and prints your pairing block. When it can't ask you directly, it stops and names the flag to rerun with: `--home`, `--profile`, `--yes` (restart), `--tailscale`, `--phone-name`. `--dry-run` shows the plan and changes nothing.

## Manual fallback

1. Choose the Hermes home and profile that run your dashboard; ask if more than one could be it.
2. Install with `hermes -p <profile> plugins install xploit9/beacon/hermes-plugin/beacon --enable`, or update with `hermes -p <profile> plugins update beacon`. Let Hermes' security scan and prompts run unchanged.
3. Ask before restarting the dashboard, then restart it the way it runs (its scheduled task or service, or `hermes -p <profile> dashboard`).
4. With a public HTTPS dashboard address configured, run `hermes -p <profile> beacon pair --name "My phone"`.

Install the latest app from [GitHub Releases](https://github.com/xploit9/beacon/releases/latest) and enter the address and code from the pairing block.
