package dev.flexaccess.ezvpn.tunnelcore

import java.net.Inet4Address
import java.net.InetAddress

/**
 * An IPv4 or IPv6 address literal, parsed without DNS. `InetAddress.getByName`
 * resolves hostnames, so callers must never hand it arbitrary text; this guards
 * with a literal-shape check first and rejects the short IPv4 forms ("10.1")
 * Java would otherwise accept.
 */
object IpLiteral {
    private val dottedQuad = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val v6Shape = Regex("""^[0-9A-Fa-f:.]+$""")

    /** The address bytes (4 or 16) of a literal, or null when `s` is not one. */
    fun parse(s: String): ByteArray? {
        val text = s.trim()
        return when {
            dottedQuad.matches(text) ->
                runCatching { (InetAddress.getByName(text) as? Inet4Address)?.address }.getOrNull()
            // An IPv4-mapped literal ("::ffff:1.2.3.4") comes back as an
            // Inet4Address on the JVM: accept whatever family Java decides.
            text.contains(':') && v6Shape.matches(text) ->
                runCatching { InetAddress.getByName(text).address }.getOrNull()
            else -> null
        }
    }

    /** True when `s` is a literal IPv4 or IPv6 address (no hostnames). */
    fun isAddress(s: String): Boolean = parse(s) != null

    /** Render 4 or 16 address bytes; IPv6 in RFC 5952 compressed form. */
    fun format(bytes: ByteArray): String {
        if (bytes.size == 4) return bytes.joinToString(".") { (it.toInt() and 0xff).toString() }
        require(bytes.size == 16) { "address must be 4 or 16 bytes" }
        val groups = IntArray(8) { i ->
            ((bytes[2 * i].toInt() and 0xff) shl 8) or (bytes[2 * i + 1].toInt() and 0xff)
        }
        // Longest run of zero groups (length >= 2) becomes "::".
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (groups[i] == 0) {
                var j = i
                while (j < 8 && groups[j] == 0) j++
                if (j - i > bestLen) { bestStart = i; bestLen = j - i }
                i = j
            } else {
                i++
            }
        }
        if (bestLen < 2) return groups.joinToString(":") { Integer.toHexString(it) }
        val head = groups.take(bestStart).joinToString(":") { Integer.toHexString(it) }
        val tail = groups.drop(bestStart + bestLen).joinToString(":") { Integer.toHexString(it) }
        return "$head::$tail"
    }
}

/**
 * A CIDR prefix over raw network-order bytes (4 for IPv4, 16 for IPv6), so one
 * implementation of overlap, containment, and subtraction serves both families
 * — the shape of ezvpn-apple's TunnelCore IPPrefix helpers.
 */
class IpPrefix private constructor(
    /** Address bytes with the host bits zeroed. */
    val bytes: ByteArray,
    val prefixLength: Int,
) : Comparable<IpPrefix> {
    val isIpv4: Boolean get() = bytes.size == 4
    val maxPrefixLength: Int get() = bytes.size * 8

    /** Dotted quad / compressed IPv6 of the network address. */
    val address: String get() = IpLiteral.format(bytes)

    /** True when the two prefixes share an address: they agree on the first min(len) bits. */
    fun overlaps(other: IpPrefix): Boolean {
        if (bytes.size != other.bytes.size) return false
        return agreeOnFirstBits(bytes, other.bytes, minOf(prefixLength, other.prefixLength))
    }

    /** True when every address of `other` lies inside this prefix. */
    fun contains(other: IpPrefix): Boolean {
        if (bytes.size != other.bytes.size || other.prefixLength < prefixLength) return false
        return agreeOnFirstBits(bytes, other.bytes, prefixLength)
    }

    fun containsAddress(addressBytes: ByteArray): Boolean =
        addressBytes.size == bytes.size && agreeOnFirstBits(bytes, addressBytes, prefixLength)

    /**
     * This prefix minus `excluded`: the (up to `maxPrefixLength - prefixLength`)
     * prefixes that cover everything here except `excluded`. Android's
     * `VpnService.Builder` had no excludeRoute before API 33, so the bypass set
     * the core computes is carved out of the routed prefixes this way instead.
     */
    fun subtract(excluded: IpPrefix): List<IpPrefix> {
        if (!overlaps(excluded)) return listOf(this)
        if (excluded.contains(this)) return emptyList()
        // `excluded` is strictly inside: split at each bit from our length down
        // to its length, keeping the sibling that does not contain it.
        val out = ArrayList<IpPrefix>()
        var current = this
        while (current.prefixLength < excluded.prefixLength) {
            val (left, right) = current.halves()
            val (keep, descend) = if (left.contains(excluded)) right to left else left to right
            out += keep
            current = descend
        }
        return out
    }

    private fun halves(): Pair<IpPrefix, IpPrefix> {
        val len = prefixLength + 1
        val hi = bytes.copyOf()
        hi[prefixLength / 8] = (hi[prefixLength / 8].toInt() or (0x80 ushr (prefixLength % 8))).toByte()
        return IpPrefix(bytes.copyOf(), len) to IpPrefix(hi, len)
    }

    override fun compareTo(other: IpPrefix): Int {
        if (bytes.size != other.bytes.size) return bytes.size - other.bytes.size
        for (i in bytes.indices) {
            val d = (bytes[i].toInt() and 0xff) - (other.bytes[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return prefixLength - other.prefixLength
    }

    override fun equals(other: Any?): Boolean =
        other is IpPrefix && prefixLength == other.prefixLength && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + prefixLength

    /** CIDR text with host bits zeroed, e.g. "192.168.1.0/24". */
    override fun toString(): String = "$address/$prefixLength"

    companion object {
        /** Parse "10.0.0.0/8" or "fd00::/8"; null on any malformed input. Host bits are zeroed. */
        fun parse(cidr: String): IpPrefix? {
            val parts = cidr.trim().split('/')
            if (parts.size != 2) return null
            val addr = IpLiteral.parse(parts[0]) ?: return null
            val len = parts[1].toIntOrNull() ?: return null
            return of(addr, len)
        }

        /** A prefix from address bytes and length; null for an out-of-range length. */
        fun of(addressBytes: ByteArray, prefixLength: Int): IpPrefix? {
            if (addressBytes.size != 4 && addressBytes.size != 16) return null
            if (prefixLength !in 0..(addressBytes.size * 8)) return null
            return IpPrefix(mask(addressBytes, prefixLength), prefixLength)
        }

        /** A single-address prefix (/32 or /128) for an address literal; null if it isn't one. */
        fun host(address: String): IpPrefix? {
            val bytes = IpLiteral.parse(address) ?: return null
            return IpPrefix(bytes, bytes.size * 8)
        }

        /** Dotted-quad netmask → prefix length (255.255.255.0 → 24); null unless contiguous. */
        fun prefixLengthOfMask(mask: String): Int? {
            val bytes = IpLiteral.parse(mask)?.takeIf { it.size == 4 } ?: return null
            var bits = 0
            var seenZero = false
            for (b in bytes) {
                for (i in 7 downTo 0) {
                    val one = (b.toInt() ushr i) and 1 == 1
                    if (one) { if (seenZero) return null; bits++ } else seenZero = true
                }
            }
            return bits
        }

        private fun mask(addressBytes: ByteArray, prefixLength: Int): ByteArray {
            val out = addressBytes.copyOf()
            for (i in out.indices) {
                val bitStart = i * 8
                if (bitStart >= prefixLength) {
                    out[i] = 0
                } else if (bitStart + 8 > prefixLength) {
                    out[i] = (out[i].toInt() and (0xff shl (8 - (prefixLength - bitStart)))).toByte()
                }
            }
            return out
        }

        private fun agreeOnFirstBits(a: ByteArray, b: ByteArray, bits: Int): Boolean {
            var remaining = bits
            var i = 0
            while (remaining >= 8) {
                if (a[i] != b[i]) return false
                i++
                remaining -= 8
            }
            if (remaining > 0) {
                val m = 0xff shl (8 - remaining)
                return (a[i].toInt() and m) == (b[i].toInt() and m)
            }
            return true
        }
    }
}

/** Route-set arithmetic shared by the service and its tests. */
object RouteMath {
    /**
     * `included` minus every prefix in `excluded`, as a sorted, deduplicated
     * list. Prefixes of the other family pass through untouched.
     */
    fun subtract(included: List<IpPrefix>, excluded: List<IpPrefix>): List<IpPrefix> {
        var current = included
        for (ex in excluded) {
            current = current.flatMap { it.subtract(ex) }
        }
        return current.toSortedSet().toList()
    }

    /**
     * Routes implied by the interface assignment itself, mirroring the Apple
     * provider's `ipv4InterfaceRoutes`/`ipv6InterfaceRoutes`: the server
     * advertises a host mask (/32, /128), so there is no on-link subnet to
     * route — only the gateway host route, which is what makes the server end
     * of the tunnel reachable with no user-configured routes. A real subnet
     * mask additionally routes that on-link subnet; a route to the assigned
     * address itself is never emitted.
     */
    fun interfaceRoutes(assigned: IpPrefix, gateway: IpPrefix?): List<IpPrefix> {
        val routes = ArrayList<IpPrefix>()
        if (assigned.prefixLength < assigned.maxPrefixLength) routes += assigned
        if (gateway != null && !assigned.contains(gateway)) routes += gateway
        return routes
    }
}
