package dev.flexaccess.ezvpn.tunnelcore

import java.text.Collator
import java.util.Locale

/** Why a proposed profile (or key) name is unacceptable. */
enum class TunnelNameError {
    /** Empty after trimming whitespace. */
    EMPTY,
    /** Case-insensitively equal to an existing name. */
    DUPLICATE,
}

sealed class NameResult {
    data class Valid(val name: String) : NameResult()
    data class Invalid(val error: TunnelNameError) : NameResult()
}

object TunnelNames {
    /** Case- and accent-insensitive equality, matching the sort order below. */
    private val collator: Collator = Collator.getInstance(Locale.ROOT).apply {
        strength = Collator.PRIMARY
    }

    /**
     * Validate a name against the existing names, returning the trimmed name
     * on success. `excluding` is the current name of the item being renamed
     * (so it can keep its own name); null when adding. Comparison ignores case
     * and diacritics, matching [comparator], so "Home" and "home" can't both
     * exist.
     */
    fun validate(raw: String, existing: List<String>, excluding: String? = null): NameResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return NameResult.Invalid(TunnelNameError.EMPTY)
        if (excluding != null && collator.equals(trimmed, excluding)) return NameResult.Valid(trimmed)
        if (existing.any { collator.equals(trimmed, it) }) return NameResult.Invalid(TunnelNameError.DUPLICATE)
        return NameResult.Valid(trimmed)
    }

    /**
     * Sort comparator for the tunnel list: case/accent-insensitive and
     * numeric-aware (the ordering the WireGuard app uses), so "tunnel2" sorts
     * before "tunnel10".
     */
    val comparator: Comparator<String> = Comparator { a, b -> naturalCompare(a, b) }

    private fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val ia = i
                val jb = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(ia, i).trimStart('0')
                val nb = b.substring(jb, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = collator.compare(ca.toString(), cb.toString())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
