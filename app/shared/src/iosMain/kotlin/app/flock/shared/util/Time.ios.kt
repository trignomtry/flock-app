package app.flock.shared.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.gettimeofday
import platform.posix.timeval

@OptIn(ExperimentalForeignApi::class)
internal actual fun currentTimeMillis(): Long = memScoped {
    val now = alloc<timeval>()
    gettimeofday(now.ptr, null)
    now.tv_sec * 1_000L + now.tv_usec.toLong() / 1_000L
}
