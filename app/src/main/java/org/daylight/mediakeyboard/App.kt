package org.daylight.mediakeyboard

import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder

class App : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        SingletonImageLoader.setSafe(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val loader = ImageLoader.Builder(context)
            .components {
                add(AnimatedImageDecoder.Factory())
                add(GifDecoder.Factory())
            }
            .build()
        loader.components.decoderFactories.forEach { factory ->
            Log.d("CoilGif", "Decoder Factory: ${factory::class.java.name}")
        }
        Log.d("CoilGif", "Has AnimatedImageDecoder: ${loader.components.toString().contains("AnimatedImageDecoder")}")
        return loader
    }
}