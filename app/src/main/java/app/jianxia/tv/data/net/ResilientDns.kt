package app.jianxia.tv.data.net

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 系统 DNS 失败时，改走公共 DNS-over-HTTPS。
 * 解析器本身用固定 IP，避免电视上的解析器再次失败。
 */
class ResilientDns : Dns {
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "jianxia-dns").apply { isDaemon = true }
    }
    private val resolvers: List<Dns> by lazy {
        listOf(
            doh("https://dns.alidns.com/dns-query", "dns.alidns.com", listOf("223.5.5.5", "223.6.6.6")),
            doh("https://doh.pub/dns-query", "doh.pub", listOf("1.12.12.12", "120.53.53.53")),
            doh("https://cloudflare-dns.com/dns-query", "cloudflare-dns.com", listOf("1.1.1.1", "1.0.0.1")),
        )
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val ascii = runCatching { java.net.IDN.toASCII(hostname) }.getOrDefault(hostname)
        literal(ascii)?.let { return listOf(it) }
        val system = systemLookup(ascii)
        if (system.isNotEmpty()) return system
        var last: Exception? = null
        for (resolver in resolvers) {
            try {
                val found = resolver.lookup(ascii)
                if (found.isNotEmpty()) return found
            } catch (error: Exception) {
                last = error
            }
        }
        throw last ?: UnknownHostException(ascii)
    }

    private fun systemLookup(hostname: String): List<InetAddress> {
        val future = executor.submit<List<InetAddress>> {
            Dns.SYSTEM.lookup(hostname)
        }
        return try {
            future.get(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
            future.cancel(true)
            emptyList()
        }
    }

    private fun literal(hostname: String): InetAddress? {
        val parts = hostname.split('.')
        if (parts.size != 4) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        if (numbers.any { it !in 0..255 }) return null
        return ipv4(hostname, hostname)
    }

    private fun doh(url: String, host: String, ips: List<String>): Dns {
        val addresses = ips.map { ipv4(host, it) }
        val bootstrap = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (hostname.equals(host, ignoreCase = true)) return addresses
                    throw UnknownHostException(hostname)
                }
            })
            .build()
        return DnsOverHttps.Builder()
            .client(bootstrap)
            .url(url.toHttpUrl())
            .includeIPv6(false)
            .bootstrapDnsHosts(addresses)
            .build()
    }

    private fun ipv4(host: String, literal: String): InetAddress {
        val bytes = literal.split('.').map { it.toInt().toByte() }.toByteArray()
        return InetAddress.getByAddress(host, bytes)
    }
}
