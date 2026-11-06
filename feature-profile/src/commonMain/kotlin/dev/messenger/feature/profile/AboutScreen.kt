package dev.messenger.feature.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.messenger.core.ui.*
import dev.messenger.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun AboutScreen(back: () -> Unit, licenses: () -> Unit) {
    AppPage(stringResource(Res.string.about_app), back) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "about") {
                    SectionCard {
                        Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineLarge)
                        Text(stringResource(Res.string.app_version), color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(Res.string.about_description))
                        TextButton(onClick = licenses) { Text(stringResource(Res.string.third_party_licenses)) }
                    }
                }
                item(key = "changelog-heading") {
                    Text(
                        stringResource(Res.string.changelog_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                items(changelogEntries, key = { it.version }) { release ->
                    SectionCard {
                        Text(
                            stringResource(Res.string.changelog_version, release.version),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.semantics { heading() },
                        )
                        release.sections.filter { it.changes.isNotEmpty() }.forEach { section ->
                            Text(
                                stringResource(section.title),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                            )
                            section.changes.forEach { change ->
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(stringResource(change), modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
