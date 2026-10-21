package org.daylight.mediakeyboard.data

class ApiException(val service: String, val code: Int, message: String?) :
    Exception("[$service] HTTP $code: $message")
