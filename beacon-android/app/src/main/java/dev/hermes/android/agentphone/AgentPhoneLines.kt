package dev.hermes.android.agentphone

import org.json.JSONObject

/**
 * The host's reason codes and their English templates (docs/agent-phone/channel-v1.md, contract revision 5 §11.1; this file is
 * generated from that table by the join's gen_lines.py). Every line an agent-phone screen shows from the host comes with its code:
 * the app shows its own translated words for a known code, with `{name}` (the assistant's chosen name) and the line's `args`
 * filled in, and the host's `line` only for an unknown or missing code. One map for every agent-phone screen: the live view, the
 * phone page, the checks and install rows, the notices and the log.
 */
internal object AgentPhoneLines {
    val templates: Map<String, String> = linkedMapOf(
        "check.cannot_run" to "This computer can't run a virtual phone.",
        "check.unsupported" to "There is no phone software for this kind of computer.",
        "check.container" to "Give the container read and write access to /dev/kvm, or run Hermes on a computer instead.",
        "check.virt_windows" to "Turn on Windows Hypervisor Platform in Windows Features, then restart the computer.",
        "check.virt_linux" to "Turn on virtualisation (KVM) on this computer.",
        "check.virt_mac" to "This Mac can't run it. Use another computer for Hermes.",
        "check.virt_unknown" to "Checked again with the test download.",
        "check.memory_low" to "The computer is low on memory. Close something there and try again.",
        "check.memory_small" to "This computer has less than 16 GB memory, so the phone may be slow.",
        "check.memory_unknown" to "Couldn't read the computer's memory.",
        "check.disk_low" to "Not enough free space. Free up {n} GB on the Hermes computer.",
        "check.disk_tight" to "Space is tight: about {n} GB free.",
        "check.network" to "The Hermes computer can't reach Google's download site.",
        "check.network_fix" to "Check the Hermes computer's internet.",
        "check.ports" to "Close other virtual phones on this computer, then try again.",
        "check.sign_in_off" to "Turn on Hermes sign-in first.",
        "check.sign_in_fix" to "Give the Hermes dashboard a sign-in and the address you open it at, then restart Hermes.",
        "check.sign_in_unread" to "Couldn't check Hermes sign-in.",
        "check.sign_in_unread_fix" to "Make sure Hermes' dashboard is on, then check again.",
        "check.dashboard_off" to "{name}'s phone can't start while Hermes' dashboard is off.",
        "check.developer_tools" to "Close Android developer tools on this computer, or apps on {name}'s phone can control the devices they connect to.",
        "install.checks" to "Check this computer first.",
        "install.licences" to "The phone uses Google's Android software. Read and accept to continue.",
        "install.offline" to "Download stopped. Check the Hermes computer's internet.",
        "install.damaged" to "A download was damaged. Getting it again.",
        "install.space" to "Not enough free space. Free up {n} GB on the Hermes computer.",
        "install.cannot_run" to "This computer can't run a virtual phone.",
        "install.update" to "Beacon needs an update to get the phone. Update Beacon, then try again.",
        "install.busy" to "The phone is already being set up.",
        "install.ports" to "Close other virtual phones on this computer, then try again.",
        "install.failed" to "Something went wrong.",
        "install.terms_first" to "Read and accept the terms first.",
        "install.nothing_to_resume" to "Nothing to resume.",
        "fix.windows" to "Turn on Windows Hypervisor Platform in Windows Features, then restart the computer.",
        "fix.linux" to "Turn on virtualisation (KVM) on this computer.",
        "fix.macosx" to "This Mac can't run it. Use another computer for Hermes.",
        "fix.unsupported" to "There is no phone software for this kind of computer.",
        "fix.broken" to "The phone software doesn't run here. Check the computer's updates, then try again.",
        "helper.no_pin" to "Saved logins on {name}'s phone need a Beacon update.",
        "helper.not_fetched" to "Saved logins on {name}'s phone need a download. Turn the phone on while online.",
        "helper.none" to "This Beacon has no helper to install.",
        "helper.damaged" to "Beacon's helper file is damaged. Update Beacon.",
        "helper.failed" to "The helper didn't install. Try again.",
        "phone.not_started" to "The phone didn't start.",
        "phone.no_keeper" to "The phone can't start on this Hermes. Update Beacon.",
        "phone.process" to "The phone's process could not start.",
        "phone.process_job" to "The phone's process could not start outside Hermes' job.",
        "phone.control" to "The phone's control line is not ready.",
        "phone.helper_damaged" to "The phone's helper file is damaged.",
        "phone.ports" to "The phone's ports are in use.",
        "phone.busy" to "The phone is busy. Try again in a minute.",
        "phone.not_set_up" to "The phone is not set up yet.",
        "phone.turn_on_first" to "Turn the phone on first.",
        "phone.not_its_folder" to "That isn't {name}'s phone folder.",
        "phone.needs_update" to "{name}'s phone needs a Beacon update.",
        "phone.none" to "{name}'s phone isn't set up yet.",
        "phone.other_profile" to "{name}'s phone belongs to another profile.",
        "hermes.busy" to "Busy, try again.",
        "hermes.updating" to "Update in progress",
        "vault.saved" to "Saved: {label} · {identifier}",
        "vault.saved_label" to "Saved: {label}",
        "vault.saved_no_username" to "Couldn't save that login: the app didn't give its username.",
        "vault.sign_in" to "Sign in to {site} on the phone.",
        "vault.login_failed" to "The {label} login didn't work.",
        "vault.code" to "Enter the code for {label}.",
        "vault.replace" to "Replace the saved {label} login?",
        "vault.save_new" to "Save the new {label} login?",
    )

    /** The words for one host line. [translate] is the screen's own lookup (uiText), [name] the assistant's chosen name. */
    fun words(reason: String?, line: String, args: Map<String, String>, name: String, translate: (String) -> String): String {
        val template = reason?.let { templates[it] }
        var text = translate(template ?: line)
        if (template != null) for ((key, value) in args) text = text.replace("{" + key + "}", value)
        return text.replace("{name}", name)
    }
}

/** A line's `args` (§11.1): the names it holds, as strings; empty when there are none. */
internal fun JSONObject.lineArgs(key: String = "args"): Map<String, String> =
    optJSONObject(key)?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } } ?: emptyMap()

/** A code field (`reason`, `error_reason`, ...), or null when it is absent, null or blank. */
internal fun JSONObject.code(key: String = "reason"): String? = (opt(key) as? String)?.takeIf { it.isNotBlank() }
