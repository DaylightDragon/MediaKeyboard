package org.daylight.mediakeyboard

import android.app.Application
import android.os.Build
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder

class App : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context).build()
//        var loader = ImageLoader.Builder(context)
//            .components {
////                if (Build.VERSION.SDK_INT >= 28) {
////                    // Явно включаем перезапись задержки, если она слишком мала
//                    add(AnimatedImageDecoder.Factory())
////                } else {
////                    add(GifDecoder.Factory(enforceMinimumFrameDelay = true))
////                }
//            }
//            .build()
//
//        Log.d("CoilGif", "Components: ${loader.components}")
//        Log.d("CoilGif", "Has AnimatedImageDecoder: ${loader.components.toString().contains("AnimatedImageDecoder")}")
//        return loader
    }
}