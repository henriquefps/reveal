package dev.hfps.reveal.designsystem.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme

/**
 * A single choice shown as a field that opens a menu (an exposed dropdown): the [label] above, the selected option's
 * label in an outlined field with a caret, and an optional [help] line under it. Choosing an option calls [onSelect]
 * and closes the menu; settings apply it at once. TalkBack reads the field as one node: label, value and "drop-down
 * list". [tag] marks the field; each option carries its own tag.
 */
@Composable
fun <T> RevealDropdown(
    label: String,
    options: List<RevealDropdownOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    help: String? = null,
    tag: String? = null,
    initiallyOpen: Boolean = false,
) {
    val colors = RevealTheme.colors
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    var fieldWidth by remember { mutableIntStateOf(0) }
    val current = options.firstOrNull { it.value == selected }?.label.orEmpty()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = RevealTheme.typography.ui, color = colors.mutedForeground)
        Box {
            DropdownField(label, current, open, tag, onWidth = { fieldWidth = it }, onOpen = { open = true })
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                containerColor = colors.card,
                shape = RevealTheme.shapes.control,
                modifier = Modifier.width(with(LocalDensity.current) { fieldWidth.toDp() }),
            ) {
                for (option in options) {
                    val isSelected = option.value == selected
                    DropdownMenuItem(
                        text = {
                            Text(option.label, style = RevealTheme.typography.body, color = colors.cardForeground)
                        },
                        trailingIcon = if (isSelected) {
                            { Icon(PhosphorRegular.Check, contentDescription = null, tint = colors.primary) }
                        } else {
                            null
                        },
                        onClick = {
                            open = false
                            if (!isSelected) onSelect(option.value)
                        },
                        colors = MenuDefaults.itemColors(textColor = colors.cardForeground),
                        modifier = Modifier
                            .semantics { this.selected = isSelected }
                            .let { if (option.tag != null) it.testTag(option.tag) else it },
                    )
                }
            }
        }
        if (help != null) Text(help, style = RevealTheme.typography.ui, color = colors.mutedForeground)
    }
}

/** The closed field: the chosen option and a caret, one TalkBack node ("<label>, <value>, drop-down list"). */
@Composable
private fun DropdownField(
    label: String,
    current: String,
    open: Boolean,
    tag: String?,
    onWidth: (Int) -> Unit,
    onOpen: () -> Unit,
) {
    val colors = RevealTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { onWidth(it.width) }
            .defaultMinSize(minHeight = 48.dp)
            .border(1.dp, if (open) colors.ring else colors.input, RevealTheme.shapes.control)
            .let { if (tag != null) it.testTag(tag) else it }
            // One node for TalkBack: "<label>, <value>, drop-down list".
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = current
                role = Role.DropdownList
                onClick {
                    onOpen()
                    true
                }
            }
            .clickable(role = Role.DropdownList, onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            current,
            style = RevealTheme.typography.body,
            color = colors.foreground,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(8.dp))
        Icon(
            if (open) PhosphorRegular.CaretUp else PhosphorRegular.CaretDown,
            contentDescription = null,
            tint = colors.mutedForeground,
            modifier = Modifier.size(20.dp),
        )
    }
}
