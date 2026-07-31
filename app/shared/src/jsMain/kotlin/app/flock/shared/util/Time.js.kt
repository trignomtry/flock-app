package app.flock.shared.util

import kotlin.js.Date

internal actual fun currentTimeMillis(): Long = Date.now().toLong()
