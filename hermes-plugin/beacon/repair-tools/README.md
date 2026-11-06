# Fixed repair stage bindings

These drivers do not configure a model key, owner fence, signing identity, device,
publisher or credentials. Root supplies those bindings. No live action is enabled
by installing this directory.

## Root installation and entry point

Copy `repair_runner.py`, `repair_executor.py`, `repair_operator.py` and
`repair-tools/trusted_stage.py` into one private trusted bundle **outside** the
repository, Orca worktree root, receipt root and model exports. Keep their relative
layout. Hash every file and executable; use those exact hashes in the executor and
operator configuration. The existing loaders reject drift before execution.

The executor's `public_manifest` must include all public source, test fixtures,
README and documentation used by checks. Keep private paths and excluded metadata
out; do not relax `_public_path`. The pinned base must describe the integrated
source in a clean Orca child. A dirty seeded overlay is not a clean committed base.
Pin the native public source commit and its tracked file manifest independently.

Build the sandbox image from this Dockerfile, with official Build Tools 35 and 36.
Complete a clean public dependency bootstrap before certifying its cache. Bake
the hash manifest and only public `modules-2` dependencies into the final image at
`/opt/beacon-repair/gradle-cache` and
`/opt/beacon-repair/gradle-cache-manifest.json`. Candidate builds always use the
fixed offline six-task command, read-only source, fresh writable scratch and no
signing material. Java JNI temp and Kotlin persistent state stay in scratch.

Calibrate the applied sandbox profile with the existing fixed no-op and isolation
probe. Pin image ID, applied profile, both harnesses, init script and cache manifest.
Use the coordinator's one global external-job Gradle slot. Retain it until the
exact nonce-owned CID is proven absent; an unconfirmed orphan never frees it.

Root's Python binding is `build_bindings(operator_config_path,
operator_config_sha256, ram_key_loader)` from `repair_operator.py`. This returns
the existing Orca allocator, fixed Grok 4.7 transport and `StageRouter`. The RAM key
loader and atomic owner-idle/current-incident fences belong to root. Never pass a
model key through argv, files in candidate exports, or candidate environment.

## Fixed trusted command arrays

For each trusted stage, configure `stages[stage]` as the existing exact
`{argv, files, cwd, env}` structure. The base argv is:

```text
[python_binary, "-I", "-B", trusted_stage_path]
```

`files` must hash that Python binary, driver, every argument naming a file, the
policy and every tool/helper executable. Paths and cwd must satisfy the existing
private/disjoint-path checks. Pin the complete bundle and dependencies in the
root installation. The operator appends these protocol arguments automatically:

```text
--request REQUEST --request-sha256 SHA256
--operator-config CONFIG --operator-config-sha256 SHA256
```

Append fixed stage arguments to the base argv:

| Stages | Required fixed arguments |
| --- | --- |
| `public_privacy`, `final_privacy` | `--policy POLICY` |
| `candidate_scan`, `final_gitleaks` | `--policy POLICY --gitleaks BINARY --gitleaks-config CONFIG` |
| `candidate_review`, `final_review` | `--policy POLICY --ocr BINARY --background-file BRIEF` |
| `prepare_release`, `commit`, `final_device`, `final_live`, `final_identity`, `release_preflight`, `publish`, `fresh_downloads` | `--owner-helper ROOT_HELPER` |

`candidate_scan` runs both privacy and Gitleaks. Privacy compares new literals
against the exact approved public baseline and runs positive controls for literal
addresses and qualified Kotlin `this` labels. Gitleaks uses fixed `dir`, JSON,
redacted report arguments and requires a real positive-control detection and a
zero-finding source scan. Neither path emits matched private text.

OCR runs `scan --repo SOURCE --path PATHS --provider ocx --model
command-code/deepseek/deepseek-v4.1-flash --audience agent --format json --output
REPORT --background BRIEF_CONTENT`. The CLI's scan interface was checked with
`scan --help`; no provider was called by this worker. The report must be completed,
cover every requested path, contain no comments/warnings and have zero tool
failures. A fixture or incomplete OCR report never certifies an external review.

The owner helper is a separately pinned fixed Python file, invoked with the same
request/config protocol plus `--stage STAGE`. Missing bindings fail before any
process starts. It must produce exactly the stage's `_ARTIFACTS` files in the
empty output directory, return zero, and preserve request/config pins. It must
honor root's atomic owner fence; the worker supplies no bypass. The operator,
outside the helper and outside model tests, writes the receipt after process exit.
Signing belongs to this trusted helper; `verify_identity` compares signed and
unsigned APK payload entries and verifies actual certificate/version metadata.

## Evidence and safe controls

The Gradle listener runs in Gradle outside the test worker JVM. Its actual test
identity/outcome inventory must match JUnit, with reconciled XML counts and no
duplicate cases. Required task rows cannot be skipped/up-to-date. The collector
also requires a host-only execution record, current output timestamps, complete
input hashes, exact command/image/profile/driver/cache pins and unsigned stable
APK checks. Only root-approved skips may pass the final verifier; skipped cases
never count as executed. Listener/task-timing integration still needs a full
current-image Gradle run before production acceptance.

These checks detect report tampering, inconsistent inventories and stale outputs;
they do not provide an OS boundary between the Gradle daemon and a hostile test
worker sharing its UID. Treat arbitrary in-process/JVM compromise as an open
adversarial proof requirement. Do not promote synthetic XML controls or an unsigned
artifact receipt into a claim that all hostile code has been attested.

Use the native pinned venv, a fresh synthetic `HERMES_HOME` established before
imports, `HERMES_DISABLE_LAZY_INSTALLS=1`, and public native source at its pinned
commit. The safe Docker control is the existing test
`test_real_combined_container_probe_and_owned_cleanup`, with
`BEACON_REPAIR_TEST_DOCKER=1`, `BEACON_REPAIR_TEST_HARNESS=1`, and
`BEACON_REPAIR_TEST_IMAGE` set to the verified toolchain image ID. It performs
isolation, actual reproduction/regression RED, validates a synthetic model-shaped
source/test proposal, runs both GREEN stages and confirms owned container cleanup.
It uses no provider or Git commits. The owner-helper positive-control test runs
only a synthetic local helper process and is not a device/live acceptance pass.

Run the complete operator/runner adapter suites in root's authorized fixture
environment before activation. Some existing tests create temporary Git commits;
the dispatched worker excluded those under its no-commit instruction. Windows Job
Object cleanup has real Windows evidence; Linux fixture success cannot certify
that platform behavior. Preserve explicit skips and any missing platform proof.
