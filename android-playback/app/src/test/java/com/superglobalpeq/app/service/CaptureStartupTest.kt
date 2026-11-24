package com.superglobalpeq.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStartupTest {
    @Test fun successfulPolicyCaptureUsesNoFallback() {
        for (sdk in 33..36) {
            val attempts = mutableListOf<CaptureRoute>()
            assertTrue(startCapture(sdk) {
                attempts += it
                true
            })
            assertEquals(listOf(CaptureRoute.POLICY), attempts)
        }
    }

    @Test fun android15And16RetryFailedPolicyCaptureWithShellContext() {
        for (sdk in 35..36) {
            val attempts = mutableListOf<CaptureRoute>()
            assertTrue(startCapture(sdk) { route ->
                attempts += route
                route == CaptureRoute.SHELL_CONTEXT
            })
            assertEquals(listOf(CaptureRoute.POLICY, CaptureRoute.SHELL_CONTEXT), attempts)
        }
    }

    @Test fun android15And16ReportFailureAfterBothCaptureRoutesFail() {
        for (sdk in 35..36) {
            val attempts = mutableListOf<CaptureRoute>()
            assertFalse(startCapture(sdk) {
                attempts += it
                false
            })
            assertEquals(listOf(CaptureRoute.POLICY, CaptureRoute.SHELL_CONTEXT), attempts)
        }
    }

    @Test fun android13And14KeepPolicyCapture() {
        for (sdk in 33..34) {
            val attempts = mutableListOf<CaptureRoute>()
            assertFalse(startCapture(sdk) {
                attempts += it
                false
            })
            assertEquals(listOf(CaptureRoute.POLICY), attempts)
        }
    }

    @Test fun android17AndLaterKeepShellContextCapture() {
        for (sdk in 37..38) {
            val attempts = mutableListOf<CaptureRoute>()
            assertFalse(startCapture(sdk) {
                attempts += it
                false
            })
            assertEquals(listOf(CaptureRoute.SHELL_CONTEXT), attempts)
        }
    }
}
