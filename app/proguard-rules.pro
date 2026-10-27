# Сохранить классы, связанные с декодером coil-gif
-keep class coil3.gif.** { *; }

# Сохранить все реализации ServiceLoader (ключевой момент)
-keep class * implements coil3.util.DecoderServiceLoaderTarget { *; }
-keep class * implements coil3.util.FetcherServiceLoaderTarget { *; }