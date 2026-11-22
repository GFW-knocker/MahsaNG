package com.v2ray.ang.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class AppFetchRoutePolicyTest {
    @Test fun connectedManualRefreshUsesOnlyConfiguredProxy() {
        val route = AppFetchRoutePolicy.choose(
            proxyExpected = true,
            configuredHttpPort = 10809,
            trigger = AppFetchRoutePolicy.Trigger.USER,
        )
        assertEquals(AppFetchRoutePolicy.Route.ViaProxy(10809), route)
    }

    @Test fun connectedRefreshWithoutValidProxyPortCannotFallBackToDirect() {
        for (port in listOf(null, 0, -1, 65536)) {
            assertEquals(
                AppFetchRoutePolicy.Route.Blocked,
                AppFetchRoutePolicy.choose(true, port, AppFetchRoutePolicy.Trigger.USER),
            )
        }
    }

    @Test fun disconnectedManualImportRequiresConsentBeforeDirectAccess() {
        assertEquals(
            AppFetchRoutePolicy.Route.Blocked,
            AppFetchRoutePolicy.choose(false, null, AppFetchRoutePolicy.Trigger.USER),
        )
        assertEquals(
            AppFetchRoutePolicy.Route.Direct,
            AppFetchRoutePolicy.choose(false, null, AppFetchRoutePolicy.Trigger.USER, directConfirmed = true),
        )
    }

    @Test fun directConsentCannotOverrideAnActiveProxyRoute() {
        assertEquals(
            AppFetchRoutePolicy.Route.ViaProxy(10809),
            AppFetchRoutePolicy.choose(true, 10809, AppFetchRoutePolicy.Trigger.USER, directConfirmed = true),
        )
    }

    @Test fun directConsentCannotEnableUnattendedBackgroundFetches() {
        assertEquals(
            AppFetchRoutePolicy.Route.Blocked,
            AppFetchRoutePolicy.choose(false, null, AppFetchRoutePolicy.Trigger.BACKGROUND, directConfirmed = true),
        )
    }

    @Test fun disconnectedBackgroundRefreshIsBlocked() {
        assertEquals(
            AppFetchRoutePolicy.Route.Blocked,
            AppFetchRoutePolicy.choose(false, 10809, AppFetchRoutePolicy.Trigger.BACKGROUND),
        )
    }

    @Test fun proxyFailureNeverRetriesWithDirectConnection() {
        var directCalled = false
        var proxyCalled = false
        try {
            AppFetchRoutePolicy.dispatch(
                route = AppFetchRoutePolicy.Route.ViaProxy(10809),
                direct = {
                    directCalled = true
                    "direct"
                },
                viaProxy = { port ->
                    assertEquals(10809, port)
                    proxyCalled = true
                    throw IOException("proxy unavailable")
                },
            )
            fail("A proxy failure must propagate to the caller")
        } catch (_: IOException) {
            assertTrue(proxyCalled)
            assertFalse(directCalled)
        }
    }

    @Test fun blockedRouteNeverOpensAnyConnection() {
        var attempted = false
        try {
            AppFetchRoutePolicy.dispatch(
                route = AppFetchRoutePolicy.Route.Blocked,
                direct = { attempted = true; "direct" },
                viaProxy = { attempted = true; "proxy" },
            )
            fail("A blocked route must throw")
        } catch (_: IOException) {
            assertFalse(attempted)
        }
    }
}
