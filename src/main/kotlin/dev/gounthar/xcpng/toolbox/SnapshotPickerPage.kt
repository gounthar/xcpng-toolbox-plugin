package dev.gounthar.xcpng.toolbox

import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.ui.actions.ActionDescription
import com.jetbrains.toolbox.api.ui.actions.RunnableActionDescription
import com.jetbrains.toolbox.api.ui.components.ComboBoxField
import com.jetbrains.toolbox.api.ui.components.UiComponents
import com.jetbrains.toolbox.api.ui.components.UiField
import com.jetbrains.toolbox.api.ui.components.UiPage
import dev.gounthar.xcpng.toolbox.xo.XoSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pick one of a VM's snapshots from a dropdown.
 *
 * This replaces a text-input popup that printed the snapshot names into its prompt and matched
 * exactly against whatever the user typed. That was not a stylistic shortcoming, and the reason it
 * is gone is worth keeping: **the first person to use it transposed the name** (`test-tbx-1` for a
 * snapshot called `tbx-test-1`) and got "No such snapshot" on a snapshot that plainly existed.
 * Measured 2026-08-19, first manual session.
 *
 * Exact matching was the right call for a destructive operation; fuzzy-matching a revert is how you
 * roll back the wrong disk. The mistake was treating "retype the name correctly" as the only
 * alternative to fuzziness. A dropdown is exact *and* cannot be mistyped, so the whole
 * near-miss class disappears rather than being traded for a different risk.
 *
 * It also removes a false dependency recorded in CONTEXT.md, which said a real picker had to wait
 * for a settings page. It does not: [com.jetbrains.toolbox.api.ui.ToolboxUi.showUiPageSuspending] is callable
 * from anywhere holding a `ToolboxUi`. There is no list-picker *popup* (the popup family is
 * info / yesNo / okCancel / textInput / snackbar and nothing more), but a [UiPage] carrying a
 * combobox is not a popup, and was available the whole time.
 *
 * @param onResult called exactly once, with the chosen snapshot or null if the user backed out.
 */
@Suppress("DEPRECATION") // ComboBoxField: see the note on `picker`.
class SnapshotPickerPage(
    vmName: String,
    private val snapshots: List<XoSnapshot>,
    private val i18n: LocalizableStringFactory,
    uiComponents: UiComponents,
    private val onResult: (XoSnapshot?) -> Unit,
) : UiPage(MutableStateFlow(i18n.pnotr("Revert \"$vmName\""))) {
    // The primary constructor takes a StateFlow rather than a plain string; the string-taking one
    // is deprecated in favour of it, because a page is allowed to retitle itself while open.

    /**
     * The selection is the snapshot **id**, never its name.
     *
     * Two snapshots of one VM may share a name: XO does not stop you, and an automated snapshot
     * schedule makes it likely rather than exotic. Keying on the id means the picker stays
     * unambiguous even when the labels are not.
     */
    private val selectedId = MutableStateFlow(snapshots.firstOrNull()?.id.orEmpty())

    private val options: List<ComboBoxField.LabelledValue<String>> = snapshots.map {
        // The short id is appended so that same-named snapshots are still distinguishable by eye.
        // Taken from the id rather than from a formatted date deliberately: a date needs a locale
        // and a zone decision this plugin has nowhere to get, and the id is already unique.
        ComboBoxField.LabelledValue(i18n.pnotr("${it.nameLabel}  ·  ${it.id.take(8)}"), it.id)
    }

    /**
     * `ComboBoxField` carries a deprecation ("deprecated together with `ComboBoxField(*)`"), and it
     * is used anyway: [UiComponents] exposes exactly two members, `icon` and `combobox`, so the API
     * offers no non-deprecated way to build a dropdown. `RichComboBoxFieldV2` exists in the jar but
     * is not reachable through [UiComponents]. Coder ships the same call. Revisit when the factory
     * grows a replacement.
     */
    private val picker: UiField = uiComponents.combobox(
        labelText = i18n.ptrl("Snapshot"),
        values = MutableStateFlow(options),
        selectedValue = selectedId,
        filterOptions = ComboBoxField.FilterOptions("Search snapshots"),
    )

    override val fields: StateFlow<List<UiField>> = MutableStateFlow(listOf(picker))

    override val description: LocalizableString =
        i18n.pnotr(
            "Reverting discards everything written since the snapshot was taken, and there is no " +
                "undo. The VM is left halted afterwards.",
        )

    /**
     * Completed by whichever of the buttons, or [cancel], fires first.
     *
     * The guard exists because more than one of those can fire for a single user gesture: a button
     * sets [RunnableActionDescription.shouldClosePage], and closing the page runs [cancel].
     * Whoever gets here first decides the answer, and the rest are no-ops.
     */
    private val answered = CompletableDeferred<Unit>()

    override val actionButtons: StateFlow<List<ActionDescription>> = MutableStateFlow(
        listOf(
            button("Revert", dangerous = true) {
                onResult(snapshots.firstOrNull { it.id == selectedId.value })
            },
            button("Cancel", dangerous = false) { onResult(null) },
        ),
    )

    /**
     * Toolbox's own dismissal: the back arrow, or the page being closed out from under us.
     *
     * Without this a user who backs out leaves the coroutine that opened this page suspended for
     * the lifetime of the plugin, holding a client factory and whatever the action was mid-way
     * through. Treated as a cancel.
     */
    override fun cancel() {
        onResultOnce(null)
        super.cancel()
    }

    // NO afterHide() OVERRIDE. It was here as a safety net for dismissal paths `cancel()` might
    // miss, on the assumption that afterHide means "this page went away". It means "this page is
    // hidden", which is not the same thing: **a combo box opens as a separate view on top, and
    // returning from it fires afterHide on the page underneath**. So picking a snapshot from the
    // dropdown completed the deferred with null and cancelled the revert: the single interaction
    // this page exists for was the one that aborted it.
    //
    // Measured 2026-08-19 across two attempts, and the pair is why it is worth writing down. The
    // first opened the dropdown, chose an item, and cancelled. The second never touched the list,
    // took the preselected first snapshot, and reverted successfully. Same build. A page with one
    // snapshot in it therefore *works*, and the bug only appears once a user has a reason to
    // choose, which is every VM with a second snapshot.
    //
    // The residual risk is a dismissal route that calls neither `cancel()` nor a button, which
    // would leave the calling coroutine suspended. That is worth accepting over a picker that
    // cannot be used, and it is cheap to revisit if a stranded revert ever shows up in the log.
    // The "revert of <uuid> cancelled." line is the thing to grep for, with Python not grep.

    private fun onResultOnce(value: XoSnapshot?) {
        if (answered.complete(Unit)) onResult(value)
    }

    private fun button(
        text: String,
        dangerous: Boolean,
        action: () -> Unit,
    ): RunnableActionDescription = object : RunnableActionDescription {
        override val label: LocalizableString = i18n.ptrl(text)
        override val isDangerous: Boolean = dangerous

        // Closing the page is what triggers afterHide, and the guard in onResultOnce is what keeps
        // that from overwriting the answer this button just gave.
        override val shouldClosePage: Boolean = true

        override fun run() {
            if (answered.complete(Unit)) action()
        }
    }
}
