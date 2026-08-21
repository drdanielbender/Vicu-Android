package com.rendyhd.vicu.quicksettings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class QuickAddTileManifestTest {

    @Test
    fun `quick add tile is a standard stateless tile service`() {
        val manifest = findManifest()
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifest)

        val service = document.getElementsByTagName("service")
            .asElements()
            .singleOrNull {
                it.androidAttribute("name") == ".quicksettings.QuickAddTileService"
            }

        assertNotNull("QuickAddTileService must be declared", service)
        requireNotNull(service)
        assertEquals("true", service.androidAttribute("exported"))
        assertEquals(
            "android.permission.BIND_QUICK_SETTINGS_TILE",
            service.androidAttribute("permission"),
        )

        val actions = service.getElementsByTagName("action")
            .asElements()
            .map { it.androidAttribute("name") }
        assertTrue(
            "QuickAddTileService must handle QS_TILE",
            "android.service.quicksettings.action.QS_TILE" in actions,
        )

        val metadataNames = service.getElementsByTagName("meta-data")
            .asElements()
            .map { it.androidAttribute("name") }
        assertFalse(
            "A stateless action tile must use standard listening mode",
            "android.service.quicksettings.ACTIVE_TILE" in metadataNames,
        )
        assertFalse(
            "A stateless action tile must not be presented as a toggle",
            "android.service.quicksettings.TOGGLEABLE_TILE" in metadataNames,
        )
    }

    private fun findManifest(): File {
        return sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).firstOrNull(File::isFile)
            ?: error("Could not locate app/src/main/AndroidManifest.xml")
    }

    private fun org.w3c.dom.NodeList.asElements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NAMESPACE, name)

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
