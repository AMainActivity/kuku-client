package dev.messenger.feature.conversation
import dev.messenger.domain.*
import dev.messenger.domain.model.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import dev.messenger.domain.model.Message
import dev.messenger.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable internal fun ReplyQuote(message:Message?,author:String?,loading:Boolean,onClick:()->Unit) {
    val preview=when {
        message==null -> stringResource(Res.string.reply_unavailable)
        message.deleted -> stringResource(Res.string.deleted_message)
        !message.plainTextOrNull().isNullOrBlank() -> message.plainTextOrNull().orEmpty()
        message.attachments.isNotEmpty() -> message.attachments.joinToString {it.filename}
        else -> stringResource(Res.string.unsupported_message)
    }
    Row(Modifier.widthIn(min=140.dp).height(IntrinsicSize.Min)
        .clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
        .clickable(onClick=onClick)) {
        Box(Modifier.fillMaxHeight().width(3.dp).background(MaterialTheme.colorScheme.primary))
        Column(Modifier.padding(horizontal=8.dp,vertical=5.dp)) {
            Text(author ?: stringResource(Res.string.reply_original),style=MaterialTheme.typography.labelMedium,
                fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(preview,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
            if(loading) LinearProgressIndicator(Modifier.width(120.dp))
        }
    }
}
