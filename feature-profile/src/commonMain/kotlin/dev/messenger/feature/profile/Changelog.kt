package dev.messenger.feature.profile

import dev.messenger.ui.resources.*
import org.jetbrains.compose.resources.StringResource

internal data class ChangelogSection(
    val title: StringResource,
    val changes: List<StringResource>,
)

internal data class ChangelogEntry(
    val version: String,
    val sections: List<ChangelogSection>,
)

// Newest first. Use Added / Changed / Fixed, omitting empty sections.
// Keep descriptions in both default and Russian resources when adding a release.
internal val changelogEntries = listOf(
    ChangelogEntry(
        version = "0.1.1",
        sections = listOf(
            ChangelogSection(
                title = Res.string.changelog_added,
                changes = listOf(Res.string.changelog_011_about),
            ),
            ChangelogSection(
                title = Res.string.changelog_changed,
                changes = listOf(Res.string.changelog_011_limits, Res.string.changelog_011_admin),
            ),
            ChangelogSection(
                title = Res.string.changelog_fixed,
                changes = listOf(
                    Res.string.changelog_011_profiles,
                    Res.string.changelog_011_groups,
                    Res.string.changelog_011_keyboard,
                ),
            ),
        ),
    ),
    ChangelogEntry(
        version = "0.1.0",
        sections = listOf(
            ChangelogSection(
                title = Res.string.changelog_added,
                changes = listOf(
                    Res.string.changelog_010_chats,
                    Res.string.changelog_010_attachments,
                    Res.string.changelog_010_offline,
                    Res.string.changelog_010_connection,
                    Res.string.changelog_010_account,
                ),
            ),
        ),
    ),
)
