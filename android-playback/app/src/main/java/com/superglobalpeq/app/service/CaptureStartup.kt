package com.superglobalpeq.app.service

internal enum class CaptureRoute(val label: String) {
    POLICY("AudioPolicy"),
    SHELL_CONTEXT("Shell context"),
}

internal fun startCapture(sdk: Int, attempt: (CaptureRoute) -> Boolean): Boolean {
    if (sdk >= 37) return attempt(CaptureRoute.SHELL_CONTEXT)
    return attempt(CaptureRoute.POLICY) || sdk == 36 && attempt(CaptureRoute.SHELL_CONTEXT)
}
