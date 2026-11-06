package dev.messenger.feature.profile

import dev.messenger.ui.resources.*
import org.jetbrains.compose.resources.StringResource

internal data class ChangelogEntry(
    val version: String,
    val title: StringResource,
    val changes: List<StringResource>,
)

// Newest first. Release versions are historical labels, not the installed app version.
// Keep descriptions in both default and Russian resources when adding a release.
internal val changelogEntries = listOf(
    ChangelogEntry(
        version = "0.1.0",
        title = Res.string.changelog_initial_release,
        changes = listOf(
            Res.string.changelog_010_chats,
            Res.string.changelog_010_attachments,
            Res.string.changelog_010_offline,
            Res.string.changelog_010_connection,
            Res.string.changelog_010_account,
        ),
    ),
)
