package dev.messenger.feature.conversation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** MUI-style bubbles: intrinsic width, capped at 75% of the available conversation width. */
@Composable
internal fun MessageBubble(own:Boolean,modifier:Modifier=Modifier,bot:Boolean=false,highlighted:Boolean=false,content:@Composable ColumnScope.()->Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth(),contentAlignment=if(own) Alignment.TopEnd else Alignment.TopStart) {
        val accent=Color(0xFFE79B45)
        Card(
            modifier=modifier.widthIn(max=maxWidth * 0.75f),
            colors=CardDefaults.cardColors(
                containerColor=when {highlighted -> MaterialTheme.colorScheme.tertiaryContainer;bot -> accent.copy(alpha=0.12f);own -> MaterialTheme.colorScheme.primaryContainer;else -> MaterialTheme.colorScheme.secondaryContainer},
                contentColor=MaterialTheme.colorScheme.onSurface),
            border=if(bot) BorderStroke(1.dp,accent.copy(alpha=0.5f)) else null,
        ) {
            Column(Modifier.padding(horizontal=12.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp),content=content)
        }
    }
}