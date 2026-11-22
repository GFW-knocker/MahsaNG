package com.v2ray.ang.dto

import java.io.Serializable

/** Every native measurement carries its originating UI-run identity. */
data class ConfigTestRequest(
    val guid: String, val config: String, val runId: String,
    val profileRevision: String = "",
) : Serializable

data class ConfigTestResult(
    val guid: String, val delayMillis: Long, val runId: String,
    val profileRevision: String = "",
) : Serializable
