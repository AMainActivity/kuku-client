@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.messenger.feature.conversation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitViewController
import platform.AVFoundation.*
import platform.AVKit.AVPlayerViewController
import platform.Foundation.*
import platform.UIKit.*
import platform.darwin.NSObject

@Composable internal actual fun LocalMediaContent(path:String,mime:String,name:String,active:Boolean,preview:Boolean,onZoom:(Boolean)->Unit,onTap:()->Unit) {
    if(mime.startsWith("image/",true)) {
        val zoomChanged by rememberUpdatedState(onZoom)
        val controller=remember(path) {
            object:UIViewController(null,null) {
                val scroll=UIScrollView()
                val picture=UIImageView(UIImage.imageWithContentsOfFile(path))
                val zoomDelegate=object:NSObject(),UIScrollViewDelegateProtocol {
                    override fun viewForZoomingInScrollView(scrollView:UIScrollView):UIView?=picture
                    override fun scrollViewDidZoom(scrollView:UIScrollView) {zoomChanged(scrollView.zoomScale>1.0)}
                }
                override fun viewDidLoad() {
                    super.viewDidLoad()
                    scroll.minimumZoomScale=1.0
                    scroll.maximumZoomScale=if(preview) 1.0 else 5.0
                    scroll.delegate=zoomDelegate
                    picture.contentMode=UIViewContentMode.UIViewContentModeScaleAspectFit
                    picture.isAccessibilityElement=true
                    picture.accessibilityLabel=name
                    scroll.addSubview(picture)
                    view.addSubview(scroll)
                }
                override fun viewDidLayoutSubviews() {
                    super.viewDidLayoutSubviews()
                    scroll.setFrame(view.bounds)
                    if(scroll.zoomScale==1.0) picture.setFrame(scroll.bounds)
                }
            }
        }
        LaunchedEffect(active) {if(!active) controller.scroll.setZoomScale(1.0,false)}
        UIKitViewController(factory={controller},modifier=Modifier.fillMaxSize())
    } else {
        val player=remember(path) {AVPlayer.playerWithURL(NSURL.fileURLWithPath(path))}
        val controller=remember(player) {AVPlayerViewController().apply {this.player=player;showsPlaybackControls=true}}
        DisposableEffect(player) {
            val token=NSNotificationCenter.defaultCenter.addObserverForName(
                UIApplicationDidEnterBackgroundNotification,null,NSOperationQueue.mainQueue) {player.pause()}
            onDispose {NSNotificationCenter.defaultCenter.removeObserver(token);player.pause();player.replaceCurrentItemWithPlayerItem(null)}
        }
        LaunchedEffect(active) {if(!active) player.pause()}
        UIKitViewController(factory={controller},modifier=Modifier.fillMaxSize())
    }
}