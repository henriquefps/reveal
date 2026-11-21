package dev.hfps.reveal.designsystem.components

/** One choice of a [RevealDropdown]: its [label] and the test [tag] of its menu item. */
data class RevealDropdownOption<T>(val value: T, val label: String, val tag: String? = null)
