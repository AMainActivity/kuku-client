package dev.messenger.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.messenger.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable fun SearchAction(onClick:()->Unit) {
    val label=stringResource(Res.string.search)
    IconButton(onClick=onClick,modifier=Modifier.semantics {contentDescription=label}) {
        val color=MaterialTheme.colorScheme.onSurface
        Canvas(Modifier.size(24.dp)) {
            drawCircle(color,radius=size.width*0.28f,center=Offset(size.width*0.42f,size.height*0.42f),style=Stroke(2.dp.toPx()))
            drawLine(color,Offset(size.width*0.63f,size.height*0.63f),Offset(size.width*0.88f,size.height*0.88f),strokeWidth=2.dp.toPx(),cap=StrokeCap.Round)
        }
    }
}

@Composable fun EmptyState(title:String,description:String,action:@Composable ()->Unit={}) {
    Column(Modifier.widthIn(max=360.dp).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(title,style=MaterialTheme.typography.titleLarge,textAlign=TextAlign.Center)
        Text(description,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center)
        action()
    }
}