package com.kantv.app.debug

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugManifestExportTest {
    @Test fun debugProbesAreNotExportedToOtherApps() {
        val manifest = File("src/debug/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("android:exported=\"true\""))
        assertTrue(manifest.contains("android:exported=\"false\""))
    }
}
