package app.gamenative.utils

import okhttp3.Dns
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * A simple DNS-over-UDP resolver that queries user-configured DNS servers.
 *
 * Supports A (IPv4) and AAAA (IPv6) record lookups via a bare-bones DNS query builder.
 * Falls back to system DNS on any failure.
 */
class CustomDnsResolver(private val servers: List<InetAddress>) : Dns {

    companion object {
        private const val DNS_PORT = 53
        private const val QUERY_TIMEOUT_MS = 5_000
        private const val DNS_FLAG_RD = 0x0100 // Recursion desired

        // TYPE values
        private const val TYPE_A = 1
        private const val TYPE_AAAA = 28
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (servers.isEmpty()) return Dns.SYSTEM.lookup(hostname)

        // Try A first (IPv4), then AAAA (IPv6)
        val results = mutableListOf<InetAddress>()

        for (dnsServer in servers) {
            try {
                // Query A record
                val aResults = query(hostname, dnsServer, TYPE_A)
                if (aResults.isNotEmpty()) {
                    results.addAll(aResults)
                    break // Found results, use them
                }
            } catch (_: Exception) {
                Timber.d("DNS query A to $dnsServer failed for $hostname")
            }
        }

        // Also try AAAA from the first successful server
        if (results.isNotEmpty()) {
            val firstServer = servers.first()
            try {
                results.addAll(query(hostname, firstServer, TYPE_AAAA))
            } catch (_: Exception) {
                // AAAA is optional
            }
        }

        return if (results.isNotEmpty()) {
            Timber.d("Custom DNS resolved $hostname → ${results.joinToString { it.hostAddress ?: "?" }}")
            results
        } else {
            Timber.w("Custom DNS could not resolve $hostname, falling back to system DNS")
            Dns.SYSTEM.lookup(hostname)
        }
    }

    private fun query(hostname: String, server: InetAddress, recordType: Int): List<InetAddress> {
        val queryPacket = buildDnsQuery(hostname, recordType)

        val socket = DatagramSocket()
        socket.soTimeout = QUERY_TIMEOUT_MS

        try {
            val request = DatagramPacket(queryPacket, queryPacket.size, InetSocketAddress(server, DNS_PORT))
            socket.send(request)

            val responseBuf = ByteArray(512)
            val response = DatagramPacket(responseBuf, responseBuf.size)
            socket.receive(response)

            return parseDnsResponse(responseBuf, response.length, recordType)
        } finally {
            socket.close()
        }
    }

    /**
     * Build a minimal DNS query packet.
     *
     * Header (12 bytes): TxID(2) + Flags(2) + QDCOUNT(2) + ANCOUNT(2) + NSCOUNT(2) + ARCOUNT(2)
     * Question: QNAME(variable) + QTYPE(2) + QCLASS(2)
     */
    private fun buildDnsQuery(hostname: String, recordType: Int): ByteArray {
        val headerSize = 12
        val qname = encodeDnsName(hostname)
        val packetSize = headerSize + qname.size + 4 // 4 = QTYPE(2) + QCLASS(2)
        val packet = ByteArray(packetSize)

        // Transaction ID (random-ish from time)
        val txId = (System.currentTimeMillis() and 0xFFFF).toInt()
        packet[0] = ((txId shr 8) and 0xFF).toByte()
        packet[1] = (txId and 0xFF).toByte()

        // Flags: standard query with recursion desired
        packet[2] = ((DNS_FLAG_RD shr 8) and 0xFF).toByte()
        packet[3] = (DNS_FLAG_RD and 0xFF).toByte()

        // QDCOUNT = 1
        packet[5] = 0x01

        // Question section
        var pos = headerSize
        System.arraycopy(qname, 0, packet, pos, qname.size)
        pos += qname.size

        // QTYPE
        packet[pos] = ((recordType shr 8) and 0xFF).toByte()
        packet[pos + 1] = (recordType and 0xFF).toByte()

        // QCLASS = IN (1)
        packet[pos + 2] = 0x00
        packet[pos + 3] = 0x01

        return packet
    }

    /**
     * Encode a hostname into DNS label format (e.g. "www.example.com" → 3www7example3com0).
     */
    private fun encodeDnsName(hostname: String): ByteArray {
        val labels = hostname.split(".")
        val size = labels.sumOf { it.length + 1 } + 1 // each label: 1 length byte + data, final 0
        val result = ByteArray(size)
        var pos = 0
        for (label in labels) {
            result[pos++] = label.length.toByte()
            for (char in label) {
                result[pos++] = char.code.toByte()
            }
        }
        result[pos] = 0
        return result
    }

    /**
     * Parse DNS response and extract IP addresses from answer section.
     */
    private fun parseDnsResponse(data: ByteArray, length: Int, recordType: Int): List<InetAddress> {
        val result = mutableListOf<InetAddress>()

        if (length < 12) return result

        // Check response flag (bit 15 in flags = QR, 1 = response)
        val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        if ((flags and 0x8000) == 0) return result // Not a response

        val anCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        if (anCount == 0) return result

        // Skip past header + question section
        var pos = 12

        // Skip question section (QNAME + QTYPE + QCLASS)
        pos = skipDnsName(data, pos)
        pos += 4 // QTYPE + QCLASS

        // Parse answers
        for (i in 0 until anCount) {
            if (pos >= length) break

            // In compressed responses, the name field may be a pointer (2 bytes starting with 0xC0)
            if ((data[pos].toInt() and 0xC0) == 0xC0) {
                pos += 2 // Skip pointer
            } else {
                pos = skipDnsName(data, pos)
            }

            if (pos + 10 > length) break

            val rType = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            // val rClass = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
            // val ttl = ((data[pos + 4].toInt() and 0xFF) shl 24) ... (skip TTL 4 bytes)
            val rdLength = ((data[pos + 8].toInt() and 0xFF) shl 8) or (data[pos + 9].toInt() and 0xFF)

            pos += 10

            if (rType == recordType && pos + rdLength <= length) {
                val ipBytes = data.copyOfRange(pos, pos + rdLength)
                try {
                    result.add(InetAddress.getByAddress(ipBytes))
                } catch (_: Exception) {
                    // Skip malformed address
                }
            }

            pos += rdLength
        }

        return result
    }

    /**
     * Skip a DNS name field (possibly compressed) and return new position.
     */
    private fun skipDnsName(data: ByteArray, start: Int): Int {
        var pos = start
        while (pos < data.size) {
            val len = data[pos].toInt() and 0xFF
            if (len == 0) {
                pos++
                break
            }
            if ((len and 0xC0) == 0xC0) {
                // Compressed name pointer (2 bytes)
                pos += 2
                break
            }
            pos += len + 1
        }
        return pos
    }
}
