package app.gamenative.utils

import app.gamenative.PrefManager
import okhttp3.Dns
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import timber.log.Timber
import java.net.InetAddress
import java.util.concurrent.TimeUnit

object Net {

    /**
     * Parses a user-input DNS server string into a list of InetAddress.
     * Supports comma, newline, semicolon, or whitespace as separators.
     */
    private fun parseDnsServers(input: String): List<InetAddress> {
        if (input.isBlank()) return emptyList()
        return input
            .split(",", "\n", ";", "\r\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { addr ->
                try {
                    InetAddress.getByName(addr)
                } catch (_: Exception) {
                    Timber.w("Invalid DNS server address: $addr")
                    null
                }
            }
    }

    /**
     * Dynamic DNS resolver that reads from PrefManager at lookup time.
     * - If custom DNS is disabled → system DNS
     * - If custom DNS is enabled but no servers configured → system DNS
     * - If custom DNS is enabled with valid servers → CustomDnsResolver
     */
    val fallbackDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val enabled = try {
                PrefManager.dnsEnabled
            } catch (_: Exception) {
                false
            }

            if (!enabled) return Dns.SYSTEM.lookup(hostname)

            val servers = try {
                parseDnsServers(PrefManager.dnsServers)
            } catch (_: Exception) {
                emptyList()
            }

            if (servers.isEmpty()) {
                Timber.d("Custom DNS enabled but no valid servers configured, using system DNS")
                return Dns.SYSTEM.lookup(hostname)
            }

            return CustomDnsResolver(servers).lookup(hostname)
        }
    }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(fallbackDns)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun httpForParallelDownloads(parallelDownloads: Int): OkHttpClient {
        val hostConcurrency = parallelDownloads.coerceIn(4, 32)
        val dispatcher = Dispatcher().apply {
            maxRequestsPerHost = hostConcurrency
            maxRequests = maxOf(64, hostConcurrency * 2)
        }
        return http.newBuilder()
            .dispatcher(dispatcher)
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }
}
