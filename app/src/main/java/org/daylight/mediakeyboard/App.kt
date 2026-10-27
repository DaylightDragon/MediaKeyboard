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
        var loader = ImageLoader.Builder(context).build()
        loader.components.decoderFactories.forEach { factory ->
            Log.d("CoilGif", "Decoder Factory: ${factory::class.java.name}")
        }
        Log.d("CoilGif", "Has AnimatedImageDecoder: ${loader.components.toString().contains("AnimatedImageDecoder")}")
        return loader
    }
}