package dev.messenger.core.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors=lightColorScheme(
    primary=Color(0xFF006B60),onPrimary=Color.White,primaryContainer=Color(0xFFC6F2E7),onPrimaryContainer=Color(0xFF00201B),
    secondary=Color(0xFF4B635D),secondaryContainer=Color(0xFFD4E8DF),
    background=Color(0xFFF3F6F5),surface=Color(0xFFF3F6F5),surfaceContainer=Color(0xFFFFFFFF),
    surfaceContainerLow=Color(0xFFFFFFFF),surfaceVariant=Color(0xFFE0E9E5),outlineVariant=Color(0xFFD1DDD7))
private val DarkColors=darkColorScheme(
    primary=Color(0xFF80D5C2),onPrimary=Color(0xFF00382F),primaryContainer=Color(0xFF155047),onPrimaryContainer=Color(0xFFBEF3E6),
    secondary=Color(0xFFB2CCC2),secondaryContainer=Color(0xFF354D44),
    background=Color(0xFF101815),surface=Color(0xFF101815),surfaceContainer=Color(0xFF1C2722),
    surfaceContainerLow=Color(0xFF17211C),surfaceVariant=Color(0xFF34473E),outlineVariant=Color(0xFF3E5047))

@Composable fun KukuTheme(dark:Boolean,content:@Composable ()->Unit) {
    MaterialTheme(colorScheme=if(dark) DarkColors else LightColors,
        shapes=Shapes(small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(18.dp),large=RoundedCornerShape(24.dp)),content=content)
}

@Composable fun SectionCard(title:String?=null,content:@Composable ColumnScope.()->Unit) {
    Surface(modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(title!=null) Text(title,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
            content()
        }
    }
}