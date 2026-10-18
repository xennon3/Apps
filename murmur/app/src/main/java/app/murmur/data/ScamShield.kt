package app.murmur.data

/**
 * Spots the shape of a scam text: a link that pretends to be someone it isn't, pushed
 * with urgency, from a stranger. Runs on the phone; nothing is looked up online.
 */
object ScamShield {

    enum class Risk { NONE, CAUTION, DANGER }

    data class Verdict(val risk: Risk, val reasons: List<String>, val links: List<String>) {
        val flagged get() = risk != Risk.NONE
    }

    private val linkRegex = Regex(
        "(?i)((?:https?://|www\\.)[^\\s<>\"]+|\\b(?:[a-z0-9-]+\\.)+(?:[a-z]{2,24})(?:/[^\\s<>\"]*)?)",
    )

    private val shorteners = setOf(
        "bit.ly", "tinyurl.com", "t.co", "is.gd", "cutt.ly", "rb.gy", "ow.ly", "goo.gl", "tiny.cc",
        "shorturl.at", "s.id", "rebrand.ly", "t.ly", "bl.ink", "v.gd", "qr.co", "lnkd.in",
    )

    private val riskyTlds = setOf(
        "top", "xyz", "click", "info", "vip", "cc", "icu", "live", "shop", "online", "site", "buzz",
        "rest", "cfd", "sbs", "cyou", "bond", "lol", "mom", "win", "loan", "work", "support",
        "help", "services", "life", "fun", "tk", "ml", "ga", "cf", "gq", "zip", "mov", "cn", "ru",
    )

    /** Brand name → domains that really belong to it. */
    private val brands = mapOf(
        "usps" to setOf("usps.com"),
        "ups" to setOf("ups.com"),
        "fedex" to setOf("fedex.com"),
        "dhl" to setOf("dhl.com"),
        "amazon" to setOf("amazon.com", "amazon.co.uk", "amazon.ca", "a.co", "amzn.to"),
        "paypal" to setOf("paypal.com"),
        "apple" to setOf("apple.com"),
        "icloud" to setOf("icloud.com"),
        "netflix" to setOf("netflix.com"),
        "chase" to setOf("chase.com"),
        "wellsfargo" to setOf("wellsfargo.com"),
        "bankofamerica" to setOf("bankofamerica.com", "bofa.com"),
        "citi" to setOf("citi.com", "citibank.com"),
        "irs" to setOf("irs.gov"),
        "ezpass" to setOf("e-zpassny.com", "ezpassnj.com", "ezpassva.com", "ezpassmd.com", "ezpass.com"),
        "fastrak" to setOf("bayareafastrak.org", "thetollroads.com"),
        "sunpass" to setOf("sunpass.com"),
        "dmv" to setOf("dmv.ca.gov", "dmv.ny.gov"),
        "coinbase" to setOf("coinbase.com"),
        "venmo" to setOf("venmo.com"),
        "zelle" to setOf("zellepay.com"),
        "walmart" to setOf("walmart.com"),
        "microsoft" to setOf("microsoft.com", "live.com"),
        "google" to setOf("google.com", "g.co", "goo.gl"),
    )

    /** Short names (UPS, IRS) only count as a whole word, so "groups" and "first" don't trip them. */
    private fun hostMentions(host: String, brand: String): Boolean {
        val labels = host.split('.', '-')
        return if (brand.length <= 3) labels.any { it == brand } else host.replace("-", "").contains(brand)
    }

    private fun textMentions(lower: String, brand: String): Boolean =
        Regex("\\b${Regex.escape(brand)}\\b").containsMatchIn(lower) ||
            (brand == "bankofamerica" && lower.contains("bank of america")) ||
            (brand == "wellsfargo" && lower.contains("wells fargo"))

    private val urgency = listOf(
        "final notice", "last notice", "suspended", "suspension", "locked", "unusual activity",
        "unpaid toll", "outstanding toll", "toll balance", "late fee", "avoid", "penalt",
        "within 24 hours", "within 12 hours", "immediately", "act now", "expire", "will be closed",
        "verify your", "confirm your", "update your", "could not be delivered", "unable to deliver",
        "redelivery", "re-delivery", "incomplete address", "address is incomplete", "package is on hold",
        "claim your", "you have won", "you've won", "congratulations", "refund", "reward", "gift card",
        "crypto", "investment opportunity", "wrong number", "job offer", "work from home", "$",
    )

    fun analyze(body: String, sender: String, senderIsContact: Boolean): Verdict {
        val reasons = LinkedHashSet<String>()
        var score = 0
        val lower = body.lowercase()
        val links = links(body)

        for (link in links) {
            val host = host(link) ?: continue
            val tld = host.substringAfterLast('.')
            val registrable = registrable(host)
            if (isIp(host)) {
                score += 4; reasons += "The link goes to a bare number address"
            }
            if (registrable in shorteners) {
                score += 2; reasons += "The link is shortened, so you can't see where it goes"
            }
            if (tld in riskyTlds) {
                score += 2; reasons += "The link ends in .$tld, popular with scam sites"
            }
            if (host.startsWith("xn--") || host.contains(".xn--")) {
                score += 4; reasons += "The link uses lookalike letters"
            }
            for ((brand, official) in brands) {
                val isOfficial = official.any { registrable == it || host.endsWith(".$it") }
                if (isOfficial) continue
                if (hostMentions(host, brand)) {
                    score += 4; reasons += "The link pretends to be ${brandName(brand)} but isn't its real site"
                } else if (textMentions(lower, brand)) {
                    score += 2; reasons += "It mentions ${brandName(brand)} but links somewhere else"
                }
            }
            if (Regex("\\btolls?\\b").containsMatchIn(lower) && !host.endsWith(".gov") && brands.values.none { registrable in it }) {
                score += 3; reasons += "It asks you to pay a toll through a site that isn't a toll agency"
            }
            if (host.count { it == '-' } >= 2 || host.split('.').size >= 5) {
                score += 1; reasons += "The link address is oddly long"
            }
        }

        val pressure = urgency.count { it != "$" && lower.contains(it) }
        if (pressure > 0 && links.isNotEmpty()) {
            score += 2 + (pressure - 1).coerceAtMost(2)
            reasons += "It pushes you to act fast"
        }
        if (Regex("reply\\s+\"?y\"?\\b.*(reopen|re-open|open the|activate)", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            score += 4; reasons += "It asks you to reply Y to unlock the link, a known scam trick"
        }

        if (!senderIsContact && (links.isNotEmpty() || pressure > 0)) {
            when {
                '@' in sender -> { score += 2; reasons += "It came from an email address, not a phone number" }
                Phone.digits(sender).let { it.length >= 11 && !it.startsWith("+1") && !it.startsWith("1") } -> {
                    score += 1; reasons += "It came from an overseas number"
                }
                else -> score += 1
            }
        }
        // People you know rarely scam you, but their phones get hijacked; only flag strong signals.
        if (senderIsContact) score -= 3

        val risk = when {
            score >= 6 -> Risk.DANGER
            score >= 3 && links.isNotEmpty() -> Risk.CAUTION
            else -> Risk.NONE
        }
        // Lead with the most telling reason.
        val ordered = reasons.sortedByDescending { r -> listOf("pretends", "scam trick", "lookalike", "bare number", "toll").any { it in r } }
        return Verdict(risk, if (risk == Risk.NONE) emptyList() else ordered, links)
    }

    fun links(body: String): List<String> =
        linkRegex.findAll(body)
            .filter { m -> m.range.first == 0 || body[m.range.first - 1] !in "@.-_" } // not the end of an email address
            .map { it.value.trimEnd('.', ',', ')', '!', '?', ';', ':') }
            .filter { v ->
                val host = host(v) ?: return@filter false
                if (v.contains('@')) return@filter false
                val tld = host.substringAfterLast('.')
                // "e.g." or "3.50" aren't links: need a letter TLD, or a full IP address after http(s)://
                (tld.length >= 2 && tld.all { it.isLetter() }) || (v.contains("://") && isIp(host))
            }.toList()

    private fun isIp(host: String) = host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))

    fun host(link: String): String? {
        val noScheme = link.substringAfter("://", link)
        val host = noScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').lowercase()
        return host.removePrefix("www.").takeIf { '.' in it }
    }

    /** "track.usps.com" → "usps.com"; good enough without the full public-suffix list. */
    private fun registrable(host: String): String {
        val parts = host.split('.')
        if (parts.size <= 2) return host
        val twoLevel = parts.takeLast(2).joinToString(".")
        return if (twoLevel in setOf("co.uk", "com.au", "co.jp", "ca.gov", "ny.gov")) parts.takeLast(3).joinToString(".") else twoLevel
    }

    private fun brandName(b: String) = when (b) {
        "usps" -> "USPS"; "ups" -> "UPS"; "dhl" -> "DHL"; "irs" -> "the IRS"; "dmv" -> "the DMV"
        "fedex" -> "FedEx"; "paypal" -> "PayPal"; "icloud" -> "iCloud"; "ezpass" -> "E-ZPass"
        "fastrak" -> "FasTrak"; "sunpass" -> "SunPass"; "wellsfargo" -> "Wells Fargo"
        "bankofamerica" -> "Bank of America"
        else -> b.replaceFirstChar { it.uppercase() }
    }
}
