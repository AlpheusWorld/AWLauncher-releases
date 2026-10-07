package ru.aw.launcher.core

import kotlinx.serialization.json.Json

val Json: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
    prettyPrint = false
}

val PrettyJson: Json = Json(from = Json) { prettyPrint = true }
