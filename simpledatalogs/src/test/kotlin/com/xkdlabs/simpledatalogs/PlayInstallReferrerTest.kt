package com.xkdlabs.simpledatalogs

import org.junit.Assert.assertFalse
import org.junit.Test

/** The install referrer reader without Google's library on the class path. */
class PlayInstallReferrerTest {
    @Test
    fun theLibraryIsOptional() {
        // The dependency is compile-only, so unit tests run without it, as an app that never added it.
        assertFalse(PlayInstallReferrer.isLibraryPresent(PlayInstallReferrerTest::class.java.classLoader))
    }
}
