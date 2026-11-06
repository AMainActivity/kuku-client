package dev.messenger.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import dev.messenger.ui.resources.*
import org.jetbrains.compose.resources.stringResource

val LocalConnectionUnavailable=staticCompositionLocalOf {false}
val LocalConnectionIndicatorEnabled=staticCompositionLocalOf {false}

val LocalOpenAppDrawer=staticCompositionLocalOf<(() -> Unit)?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AppToolbar(title:String,back:(()->Unit)?=null,actions:@Composable RowScope.()->Unit={},titleContent:(@Composable ()->Unit)?=null) {
    val openDrawer=LocalOpenAppDrawer.current
    val unavailable=LocalConnectionUnavailable.current
    val description=if(unavailable) stringResource(Res.string.connection_reconnecting) else null
    Box(if(description!=null) Modifier.semantics {stateDescription=description;liveRegion=LiveRegionMode.Polite} else Modifier) {
    TopAppBar(title={if(titleContent!=null) titleContent() else Text(title,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)},navigationIcon={
        val action=back ?: openDrawer
        if(action!=null) {
            val description=stringResource(if(back!=null) Res.string.back else Res.string.open_menu)
            IconButton(onClick=action,modifier=Modifier.semantics {contentDescription=description}) {
                val color=MaterialTheme.colorScheme.onSurface
                val rtl=LocalLayoutDirection.current==LayoutDirection.Rtl
                Canvas(Modifier.size(24.dp)) {
                    fun point(x:Float,y:Float)=Offset(size.width*(if(back!=null && rtl) 24-x else x)/24,size.height*y/24)
                    fun line(x:Float,y:Float,x2:Float,y2:Float)=drawLine(color,point(x,y),point(x2,y2),2.dp.toPx(),StrokeCap.Round)
                    if(back!=null) {line(19f,12f,5f,12f);line(5f,12f,11f,6f);line(5f,12f,11f,18f)}
                    else {line(4f,6f,20f,6f);line(4f,12f,20f,12f);line(4f,18f,20f,18f)}
                }
            }
        }
    },actions=actions)
    if(unavailable) LinearProgressIndicator(
        modifier=Modifier.fillMaxWidth().height(3.dp).align(androidx.compose.ui.Alignment.BottomCenter),
        color=MaterialTheme.colorScheme.error,
        trackColor=MaterialTheme.colorScheme.errorContainer)
    }
}

@Composable fun AppPage(title:String,back:(()->Unit)?=null,actions:@Composable RowScope.()->Unit={},titleContent:(@Composable ()->Unit)?=null,content:@Composable ()->Unit) {
    Scaffold(topBar={AppToolbar(title,back,actions,titleContent)}) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {content()}
    }
}