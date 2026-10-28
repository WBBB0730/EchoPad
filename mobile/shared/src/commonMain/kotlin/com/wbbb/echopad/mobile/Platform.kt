package com.wbbb.echopad.mobile

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform