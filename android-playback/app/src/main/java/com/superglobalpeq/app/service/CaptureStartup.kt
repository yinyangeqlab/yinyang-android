package com.superglobalpeq.app.service

private const val ANDROID_15 = 35
private const val ANDROID_17 = 37

internal enum class CaptureRoute(val label: String) {
    POLICY("AudioPolicy"),
    SHELL_CONTEXT("Shell context"),
}

internal fun startCapture(sdk: Int, attempt: (CaptureRoute) -> Boolean): Boolean {
    if (sdk >= ANDROID_17) return attempt(CaptureRoute.SHELL_CONTEXT)
    return attempt(CaptureRoute.POLICY) ||
        (sdk >= ANDROID_15 && attempt(CaptureRoute.SHELL_CONTEXT))
}
