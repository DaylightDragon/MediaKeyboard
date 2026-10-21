package org.daylight.mediakeyboard.data

class MissingApiKeyException(val service: String) :
    Exception("API key for '$service' is not set")
