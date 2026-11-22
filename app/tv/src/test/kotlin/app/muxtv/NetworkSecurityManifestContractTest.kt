package app.muxtv

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

class NetworkSecurityManifestContractTest {
    @Test
    fun manifestUsesNetworkSecurityConfigForDynamicIptvCleartextCapability() {
        val manifest = parseXml(projectFile("src/main/AndroidManifest.xml"))
        val application = manifest.documentElement
            .getElementsByTagName("application")
            .item(0) as Element

        assertThat(application.getAttributeNS(ANDROID_NAMESPACE, "networkSecurityConfig"))
            .isEqualTo("@xml/network_security_config")
        assertThat(application.hasAttributeNS(ANDROID_NAMESPACE, "usesCleartextTraffic")).isFalse()

        val config = parseXml(projectFile("src/main/res/xml/network_security_config.xml"))
        val baseConfigs = config.documentElement.getElementsByTagName("base-config")

        assertThat(baseConfigs.length).isEqualTo(1)
        assertThat((baseConfigs.item(0) as Element).getAttribute("cleartextTrafficPermitted"))
            .isEqualTo("true")
    }

    private fun parseXml(file: File) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)

    private fun projectFile(relativePath: String): File {
        val fromModule = File(relativePath)
        if (fromModule.isFile) return fromModule

        val fromRoot = File("app/tv/$relativePath")
        check(fromRoot.isFile) { "Missing required project file: $relativePath" }
        return fromRoot
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
