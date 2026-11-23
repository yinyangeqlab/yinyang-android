package com.superglobalpeq.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStartupTest {
    @Test fun successfulPolicyCaptureUsesNoFallback() {
        val attempts = mutableListOf<CaptureRoute>()
        assertTrue(startCapture(36) { attempts += it; true })
        assertEquals(listOf(CaptureRoute.POLICY), attempts)
    }

    @Test fun android16RetriesFailedPolicyCaptureWithShellContext() {
        val attempts = mutableListOf<CaptureRoute>()
        assertTrue(startCapture(36) { route ->
            attempts += route
            route == CaptureRoute.SHELL_CONTEXT
        })
        assertEquals(listOf(CaptureRoute.POLICY, CaptureRoute.SHELL_CONTEXT), attempts)
    }

    @Test fun android16ReportsFailureAfterBothCaptureRoutesFail() {
        val attempts = mutableListOf<CaptureRoute>()
        assertFalse(startCapture(36) { attempts += it; false })
        assertEquals(listOf(CaptureRoute.POLICY, CaptureRoute.SHELL_CONTEXT), attempts)
    }

    @Test fun android13Through15KeepPolicyCapture() {
        for (sdk in 33..35) {
            val attempts = mutableListOf<CaptureRoute>()
            assertFalse(startCapture(sdk) { attempts += it; false })
            assertEquals(listOf(CaptureRoute.POLICY), attempts)
        }
    }

    @Test fun android17AndLaterKeepShellContextCapture() {
        for (sdk in 37..38) {
            val attempts = mutableListOf<CaptureRoute>()
            assertFalse(startCapture(sdk) { attempts += it; false })
            assertEquals(listOf(CaptureRoute.SHELL_CONTEXT), attempts)
        }
    }
}
