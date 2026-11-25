package com.pais.handy

import android.Manifest
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class InstallPermissionsTest {
    @Test fun mergedManifestMatchesTheSelectedBuild() {
        val manifest = File(checkNotNull(System.getProperty("handy.test.manifest")))
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        fun attributes(tag: String, name: String): List<String> {
            val elements = document.getElementsByTagName(tag)
            return (0 until elements.length).map {
                (elements.item(it) as Element).getAttributeNS("http://schemas.android.com/apk/res/android", name)
            }
        }
        val bindings = attributes("service", "permission")
        assertTrue(Manifest.permission.BIND_INPUT_METHOD in bindings)
        assertTrue(Manifest.permission.RECORD_AUDIO in attributes("uses-permission", "name"))
        val withButton = System.getProperty("handy.test.dictationButton").toBoolean()
        assertEquals(withButton, Manifest.permission.BIND_ACCESSIBILITY_SERVICE in bindings)
        assertEquals(withButton, "android.accessibilityservice.AccessibilityService" in attributes("action", "name"))
    }
}
