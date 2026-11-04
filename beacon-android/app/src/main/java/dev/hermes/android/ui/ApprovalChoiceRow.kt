package dev.hermes.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.hermes.android.phone.ApprovalChoice
import dev.hermes.android.phone.PhoneAccess

/** The words of each choice in Approvals (owner, 4 Nov 2026: Manual, Auto, Full access), and what it means, one short line each. */
internal fun approvalChoiceLabel(choice: ApprovalChoice): String = when (choice) {
    ApprovalChoice.Always -> "Manual"
    ApprovalChoice.Changes -> "Auto"
    ApprovalChoice.Never -> "Full access"
}

internal fun approvalChoiceMeaning(choice: ApprovalChoice): String = when (choice) {
    ApprovalChoice.Always -> "Hermes asks before it reads or changes anything"
    ApprovalChoice.Changes -> "Hermes asks before it changes anything"
    ApprovalChoice.Never -> "Hermes acts without asking. Phone calls still ask."
}

/**
 * Approvals: one menu for whether Hermes asks before it acts, in chats, calls and from the side button.
 * [onChanged] tells the page the choice moved (Phone access words its footer from it).
 */
@Composable internal fun ApprovalChoiceRow(onChanged: (ApprovalChoice) -> Unit = {}) {
    val context = LocalContext.current
    var choice by remember { mutableStateOf(PhoneAccess.approvalChoice(context)) }
    SettingsChoiceRow(
        label = uiText("Approvals"), selected = choice.name, options = ApprovalChoice.entries.map { it.name },
        onSelect = { id -> ApprovalChoice.valueOf(id).let { PhoneAccess.setApprovalChoice(context, it); choice = it; onChanged(it) } },
        icon = Icons.Rounded.VerifiedUser, optionLabels = ApprovalChoice.entries.associate { it.name to uiText(approvalChoiceLabel(it)) },
        optionTestTags = ApprovalChoice.entries.associate { it.name to "approval-choice-${it.name}" },
        style = ChoiceStyle.Menu, anchorTestTag = "approval-choice", valueTestTag = "approval-choice-value",
    )
}
