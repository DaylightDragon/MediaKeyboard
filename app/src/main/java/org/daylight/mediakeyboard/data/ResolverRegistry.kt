package org.daylight.mediakeyboard.data

import org.daylight.mediakeyboard.data.klipy.KlipyResolver

class ResolverRegistry(
    private val resolvers: List<MediaResolver>
) {
    fun find(url: String): MediaResolver? =
        resolvers.firstOrNull { it.detect(url) }

    fun byId(serviceId: String): MediaResolver? =
        resolvers.firstOrNull { it.serviceId == serviceId }

    fun all(): List<MediaResolver> = resolvers

    companion object {
        fun createDefault() = ResolverRegistry(listOf(KlipyResolver()))
    }
}
