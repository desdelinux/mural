package chat.mural.core

import org.junit.Assert.*
import org.junit.Test

class ProviderFailureTest {
    @Test fun creditAndTemporaryLimitsRequireDifferentActions() {
        assertEquals(ProviderFailureKind.quota, ProviderFailureKind.classify(429, "insufficient_quota"))
        assertEquals(ProviderFailureKind.rateLimit, ProviderFailureKind.classify(429, "rate_limit_exceeded"))
        assertEquals(ProviderFailureKind.authentication, ProviderFailureKind.classify(401, "insufficient_quota"))
        assertEquals(ProviderFailureKind.unavailable, ProviderFailureKind.classify(503, null))
    }
    @Test fun arbitraryProviderDetailsCannotBecomeVisibleReferencesOrCategories() {
        assertNull(ProviderFailureKind.safeCode("private_value"))
        assertNull(ProviderFailureKind.safeReference("private\nheader"))
        assertNull(ProviderFailureKind.safeReference("x".repeat(129)))
        assertEquals("req_support", ProviderFailureKind.safeReference("req_support"))
    }
}
