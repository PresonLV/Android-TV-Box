package app.jianxia.core

import app.jianxia.core.backup.UrlList
import app.jianxia.core.model.SiteKind
import app.jianxia.core.parser.ConfigDecoder
import app.jianxia.core.parser.TvBoxConfigParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ConfigDecoderTest {
    @Test
    fun base64BodyBecomesJson() {
        val json = """{"sites":[{"key":"a","name":"示例","type":1,"api":"https://example.test/vod"}]}"""
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray())
        val decoded = ConfigDecoder.decode(encoded.toByteArray(), "text/plain")
        val config = TvBoxConfigParser.parse(decoded)
        assertEquals(SiteKind.MACCMS_JSON, config.sites.single().kind)
        assertEquals("https://example.test/vod", config.sites.single().api)
    }

    @Test
    fun imageHidesJsonAfterMarker() {
        val json = """{"sites":[{"key":"spider","name":"爬虫","type":3,"api":"csp_Demo"}]}"""
        val payload = "abcd1234**" + Base64.getEncoder().encodeToString(json.toByteArray())
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            "JFIF".toByteArray() +
            payload.toByteArray()
        val decoded = ConfigDecoder.decode(jpeg, "image/jpeg")
        val config = TvBoxConfigParser.parse(decoded)
        assertEquals(SiteKind.UNSUPPORTED, config.sites.single().kind)
        assertTrue(config.sites.single().unsupportedReason!!.contains("JAR/JS"))
    }

    @Test
    fun commentsAndTrailingCommasParse() {
        val raw = """
            {
              // 行注释
              "sites": [
                {"key": "a", "name": "示例", "type": 0, "api": "https://example.test/xml",}
              ],
              "wallpaper": "https://example.test/a.jpg",
            }
        """.trimIndent()
        val config = TvBoxConfigParser.parse(raw, "https://example.test/box/cfg.json")
        assertEquals(SiteKind.MACCMS_XML, config.sites.single().kind)
        assertEquals("https://example.test/xml", config.sites.single().api)
        assertEquals("https://example.test/a.jpg", config.wallpaper)
    }

    @Test
    fun relativeUrlsResolveAgainstConfig() {
        val raw = """
            {"sites":[{"key":"a","name":"示例","type":1,"api":"./vod"},{"key":"s","name":"爬虫","type":3,"api":"csp_Demo"}],
             "lives":[{"name":"直播","url":"./live.m3u","epg":"../epg.xml"}]}
        """.trimIndent()
        val config = TvBoxConfigParser.parse(raw, "https://example.test/box/cfg.json")
        assertEquals("https://example.test/box/vod", config.sites[0].api)
        assertEquals("csp_Demo", config.sites[1].api)
        assertEquals(SiteKind.UNSUPPORTED, config.sites[1].kind)
        assertEquals("https://example.test/box/live.m3u", config.lives.single().url)
        assertEquals("https://example.test/epg.xml", config.lives.single().epgUrl)
    }

    @Test
    fun aes2423RoundTrip() {
        val json = """{"sites":[],"lives":[{"name":"直播","url":"https://example.test/a.m3u"}]}"""
        val encoded = encrypt2423(json, key = "abc123", iv = "iv-sample-13!")
        assertTrue(encoded.startsWith("2423"))
        val decoded = ConfigDecoder.normalize(encoded)
        assertTrue(decoded.contains("example.test/a.m3u"))
        assertEquals("https://example.test/a.m3u", TvBoxConfigParser.parse(decoded).lives.single().url)
    }

    @Test
    fun urlListDetectsOneAddressPerLine() {
        val found = UrlList.extract(" https://example.test/a \n\nhttp://example.test/b ")
        assertEquals(listOf("https://example.test/a", "http://example.test/b"), found)
        assertEquals(listOf("https://example.test/only"), UrlList.extract("https://example.test/only"))
        assertNull(UrlList.extract("""{"version":1}"""))
        assertNull(UrlList.extract("这不是网址"))
    }

    @Test
    fun englishFailuresBecomeChinese() {
        val jsonError = IllegalArgumentException(
            "Unexpected JSON token at offset 0: Expected start of the object '{', but had 'h' instead at path: \$ JSON input: http://example.test/tv",
        )
        assertEquals(UserFacingError.NOT_BACKUP, UserFacingError.message(jsonError))
        val dns = java.net.UnknownHostException("Unable to resolve host \"xn--example.test\": No address associated with hostname")
        assertEquals(UserFacingError.DNS, UserFacingError.message(dns))
        assertEquals(UserFacingError.TIMEOUT, UserFacingError.message(java.net.SocketTimeoutException("timeout")))
        assertEquals("请输入以 http:// 或 https:// 开头的地址", UserFacingError.message(IllegalArgumentException("请输入以 http:// 或 https:// 开头的地址")))
    }

    private fun encrypt2423(json: String, key: String, iv: String): String {
        val paddedKey = (key + "0000000000000000").take(16)
        val paddedIv = (iv + "0000000000000000").take(16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(paddedKey.toByteArray(), "AES"),
            IvParameterSpec(paddedIv.toByteArray()),
        )
        val encrypted = cipher.doFinal(json.toByteArray())
        val packed = "$#$key#$".toByteArray(Charsets.ISO_8859_1) + encrypted + iv.toByteArray(Charsets.ISO_8859_1)
        return packed.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
