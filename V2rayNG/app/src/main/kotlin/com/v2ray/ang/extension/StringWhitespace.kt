package com.v2ray.ang.extension

/** Normalizes user-entered comma-separated addresses and URI settings. */
fun String.removeWhiteSpace(): String = filterNot { it.isWhitespace() }
