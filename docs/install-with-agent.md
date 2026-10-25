# Install Beacon with your agent

## Before installing Beacon

This is a clean break from the previous plugin identity. The Beacon installer does not migrate or remove a predecessor plugin or its scheduled work. Complete these steps first, including when using the manual fallback:

1. Select the Hermes home/profile that runs your dashboard. Inventory its plugins and native cron jobs, including disabled jobs, using supported Hermes commands or the dashboard. Positively identify the predecessor plugin and its managed job IDs from its manifest, saved ownership metadata and job/script definitions; a similar name alone is not proof. If anything is uncertain, stop and ask the owner before removing it.
2. Back up the predecessor's plugin files, configuration, data and identified job definitions outside all plugin-discovery directories. Keep that backup private and outside the Beacon checkout. Verify that the copy is complete and readable (compare file lists and hashes) before any removal; preserve Hermes conversations, memories, unrelated jobs and credentials.
3. Use supported native Hermes operations in the selected home/profile to disable the predecessor, remove only its positively identified managed cron jobs, and remove the predecessor plugin. Recheck the native inventories to confirm those items are gone and unrelated work remains. Do not run broad deletion commands or add retired-identity aliases to Beacon.

If no predecessor is installed, confirm that from the inventories and proceed without removing anything. Retain the verified backup for recovery.

## Run the installer

On the PC that runs Hermes, download [`beacon_install.py`](https://raw.githubusercontent.com/xploit9/beacon/main/hermes-plugin/beacon/install/beacon_install.py) and run it with Python 3.10 or newer:

```sh
python beacon_install.py
```

The installer selects the Hermes home and profile (it asks when more than one could be yours), installs or updates Beacon with Hermes' own installer, restarts the dashboard if you say yes, and prints the link to paste into Beacon. When it can't ask you directly, it stops and names the flag to rerun with: `--home`, `--profile`, `--yes` (restart), `--tailscale`. `--dry-run` shows the plan and changes nothing.

## Manual fallback

1. Choose the Hermes home and profile that run your dashboard; ask if more than one could be it.
2. Complete **Before installing Beacon** above: verify the external backup and use native operations to remove only the identified predecessor-managed jobs and plugin.
3. Install with `hermes -p <profile> plugins install xploit9/beacon/hermes-plugin/beacon --enable`, or update with `hermes -p <profile> plugins update beacon`. Let Hermes' security scan and prompts run unchanged.
4. Ask before restarting the dashboard, then restart it the way it runs (its scheduled task or service, or `hermes -p <profile> dashboard`).
5. Configure a public HTTPS dashboard address; that address is the link you paste into Beacon.

Install the latest app from [GitHub Releases](https://github.com/xploit9/beacon/releases/latest), paste the link and sign in with Hermes. Signing in links the phone.
