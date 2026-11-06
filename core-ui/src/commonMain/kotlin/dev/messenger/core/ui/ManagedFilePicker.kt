package dev.messenger.core.ui
import dev.messenger.domain.*
import dev.messenger.domain.model.*
import androidx.compose.runtime.Composable
import dev.messenger.domain.FileStorage
@Composable expect fun rememberManagedFilePicker(accountKey:String, files:FileStorage, maxFiles:Int, maxBytes:Long, mimeTypes:List<String>, onPicked:(String,String,String)->Unit, onError:(String)->Unit): () -> Unit

