package com.wbbb.echopad.mobile

/** 各平台提供给共享代码的能力，由 Android、iOS 的入口创建。 */
class Platform(
    val deviceName: String,
    val storage: LocalStorage,
)
