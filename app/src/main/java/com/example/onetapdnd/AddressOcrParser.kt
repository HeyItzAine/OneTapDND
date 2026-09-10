package com.example.onetapdnd

import kotlin.math.absoluteValue

data class OcrLocationGuess(
    val address: String?,
    val placeName: String?,
    val latitude: Double?,
    val longitude: Double?
)

object AddressOcrParser {
    private const val MAX_TEXT_LENGTH = 30_000
    private const val MAX_LINES = 250
    private const val MAX_LINE_LENGTH = 300

    private val street = Regex(
        """(?i)(?:^|\b)(?:street|st\.?|road|rd\.?|avenue|ave\.?|boulevard|blvd\.?|lane|ln\.?|drive|dr\.?|court|ct\.?|parkway|pkwy\.?|highway|hwy\.?|route|way|terrace|place|plaza|square|rue|chemin|straße|strasse|str\.?|weg|via|viale|piazza|calle|carrera|rua|rodovia|jalan|jl\.?|gang|gg\.?|lorong|komplek|kompleks|desa|dusun|soi|ถนน|丁目|番地)(?:\b|\s)"""
    )
    private val administration = Regex(
        """(?i)\b(?:district|county|city|town|village|municipality|province|state|prefecture|regency|borough|parish|region|kecamatan|kec\.?|kabupaten|kab\.?|kelurahan|desa|kota|provinsi|barangay|subdistrict|taluk|tehsil|oblast|okrug|departamento)\b"""
    )
    private val addressPrefix = Regex(
        """(?i)^(?:address|alamat|location|lokasi|dirección|adresse|indirizzo|endereço|endereco|anschrift)\s*[:\-]\s*"""
    )
    private val postalCode = Regex(
        """(?ix)(?:
            \b\d{5}(?:-\d{4})?\b |
            \b\d{4}\s?[A-Z]{2}\b |
            \b[A-Z]\d[A-Z]\s?\d[A-Z]\d\b |
            \b[A-Z]{1,2}\d[A-Z\d]?\s?\d[A-Z]{2}\b |
            \b\d{3}-\d{4}\b |
            \b\d{4}-\d{3}\b |
            \b\d{5}-\d{3}\b |
            \b\d{6}\b
        )"""
    )
    private val plusCode = Regex(
        """(?i)(?<![A-Z0-9])[23456789CFGHJMPQRVWX]{4,8}\+[23456789CFGHJMPQRVWX]{2,3}(?![A-Z0-9])"""
    )
    private val loosePlusCode = Regex(
        """(?i)(?<![A-Z0-9])[23456789CFGHJMPQRVWX0O]{4,8}\s*\+\s*[23456789CFGHJMPQRVWX0O]{2,3}(?![A-Z0-9])"""
    )
    private val country = Regex(
        """(?i)\b(?:united states|usa|united kingdom|uk|canada|australia|new zealand|indonesia|india|japan|china|singapore|malaysia|thailand|philippines|vietnam|germany|france|spain|italy|brazil|mexico|netherlands|belgium|switzerland|austria|ireland|south africa|west java|east java|central java)\b"""
    )
    private val coordinatePair = Regex(
        """(?<![\d.])([+-]?(?:90(?:\.0+)?|[0-8]?\d(?:\.\d+)?))\s*[,;]\s*([+-]?(?:180(?:\.0+)?|1[0-7]\d(?:\.\d+)?|\d?\d(?:\.\d+)?))(?![\d.])"""
    )
    private val mapUrlPair = Regex(
        """(?i)(?:@|query=|q=)([+-]?\d{1,2}(?:\.\d+)?)(?:,|%2C|\s)+([+-]?\d{1,3}(?:\.\d+)?)"""
    )
    private val cardinalPair = Regex(
        """(?i)(\d{1,2}(?:\.\d+)?)\s*[°º]?\s*([NS])\s*[,; ]+\s*(\d{1,3}(?:\.\d+)?)\s*[°º]?\s*([EW])"""
    )
    private val labeledLatitude = Regex("""(?i)\b(?:lat|latitude)\s*[:=]?\s*([+-]?\d{1,2}(?:[.,]\d+)?)""")
    private val labeledLongitude = Regex("""(?i)\b(?:lon|lng|longitude)\s*[:=]?\s*([+-]?\d{1,3}(?:[.,]\d+)?)""")
    private val junk = Regex(
        """(?i)^(?:overview|menu|reviews?|photos?|updates?|directions?|order(?: online)?|call|share|save|website|open|closed|closes?\b.*|popular times?|suggest an edit|update location|ask|navigate|start|nearby|more|about|posts?|services?|products?|accessibility|payments?|highlights?|from the menu|people also search for|busy area|live\b.*|less busy than usual|more busy than usual|mon(?:day)?s?|tue(?:sday)?s?|wed(?:nesday)?s?|thu(?:rsday)?s?|fri(?:day)?s?|sat(?:urday)?s?|sun(?:day)?s?)$"""
    )
    private val sentenceNoise = Regex(
        """(?i)^(?:some say|users? say|people say|according to|located in|known for|during busy|the food|this place|a reviewer|one reviewer|recent reviews?)\b"""
    )
    private val phone = Regex("""^\+?[\d() .-]{8,}$""")
    private val rating = Regex("""^\d(?:[.,]\d)?\s*(?:\(.*\)|stars?)?$""", RegexOption.IGNORE_CASE)

    fun parse(rawText: String): OcrLocationGuess {
        val bounded = repairPlusCodes(rawText.take(MAX_TEXT_LENGTH))
        val lines = bounded.lineSequence().take(MAX_LINES).map(::cleanLine)
            .filter { it.isNotBlank() }.toList()
        val coordinates = findCoordinates(lines)
        val addressCandidate = findAddress(lines)
        val name = addressCandidate?.let { findPlaceName(lines, it.first) }
        return OcrLocationGuess(
            address = addressCandidate?.second,
            placeName = name,
            latitude = coordinates?.first,
            longitude = coordinates?.second
        )
    }

    fun coordinatesFromSearchText(value: String): Pair<Double, Double>? {
        val lines = repairPlusCodes(value.take(MAX_TEXT_LENGTH))
            .lineSequence()
            .take(MAX_LINES)
            .map(::cleanLine)
            .filter { it.isNotBlank() }
            .toList()
        return findCoordinates(lines)
    }

    fun searchQueryCandidates(value: String): List<String> {
        val normalized = normalizeSearchText(value)
        if (normalized.isBlank()) return emptyList()
        val withoutLabel = normalized.replace(addressPrefix, "").trim()
        val withoutSeparators = withoutLabel.replace(Regex("""\s*[,;|]+\s*"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return listOf(normalized, withoutLabel, withoutSeparators)
            .filter { it.isNotBlank() }
            .distinct()
    }

    /** Converts pasted or OCR line breaks into a stable Android Geocoder query. */
    fun normalizeSearchText(value: String): String = repairPlusCodes(
        value.take(MAX_TEXT_LENGTH)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .take(MAX_LINES)
            .map(::cleanLine)
            .filter { it.isNotBlank() }
            .joinToString(", ")
    ).replace(Regex("""\s*,\s*"""), ", ")
        .replace(Regex("""(?:,\s*){2,}"""), ", ")
        .trim(' ', ',')

    private fun repairPlusCodes(value: String): String = value.replace(loosePlusCode) { match ->
        match.value.replace(Regex("""\s+"""), "")
            .replace('0', 'Q').replace('O', 'Q').replace('o', 'Q')
    }

    private fun cleanLine(value: String): String = value.take(MAX_LINE_LENGTH)
        .replace('\u00A0', ' ')
        .replace(Regex("""(?i)\b(jl|kec|kab)\s+\."""), "$1.")
        .replace(Regex("""^[•●▪◦·|>]+\s*"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun findCoordinates(lines: List<String>): Pair<Double, Double>? {
        val text = lines.joinToString(" ")
        mapUrlPair.find(text)?.let { match ->
            validated(match.groupValues[1].toDoubleOrNull(), match.groupValues[2].toDoubleOrNull())?.let { return it }
        }
        cardinalPair.find(text)?.let { match ->
            var latitude = match.groupValues[1].toDouble()
            var longitude = match.groupValues[3].toDouble()
            if (match.groupValues[2].equals("S", true)) latitude = -latitude
            if (match.groupValues[4].equals("W", true)) longitude = -longitude
            validated(latitude, longitude)?.let { return it }
        }
        val latitude = labeledLatitude.find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        val longitude = labeledLongitude.find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        validated(latitude, longitude)?.let { return it }
        lines.forEach { line ->
            coordinatePair.find(line)?.let { match ->
                val first = match.groupValues[1]
                val second = match.groupValues[2]
                val explicitlyCoordinates = line.contains(Regex("""(?i)\b(?:coordinates?|coords?|lat|location|gps)\b"""))
                if (explicitlyCoordinates || first.contains('.') || second.contains('.') || first.startsWith('-') || first.startsWith('+')) {
                    validated(first.toDoubleOrNull(), second.toDoubleOrNull())?.let { return it }
                }
            }
        }
        return null
    }

    private fun validated(latitude: Double?, longitude: Double?): Pair<Double, Double>? {
        if (latitude == null || longitude == null || !latitude.isFinite() || !longitude.isFinite()) return null
        if (latitude.absoluteValue > 90 || longitude.absoluteValue > 180) return null
        return latitude to longitude
    }

    private fun findAddress(lines: List<String>): Pair<Int, String>? {
        val scores = lines.map(::addressScore)
        var best: Triple<Int, Int, Int>? = null
        scores.indices.filter { scores[it] >= 4 && !isJunk(lines[it]) }.forEach { anchor ->
            var start = anchor
            while (start > 0 && anchor - start < 2 && scores[start - 1] >= 3 && !isJunk(lines[start - 1])) start--
            var end = anchor
            val anchorHasStreet = street.containsMatchIn(lines[anchor])
            while (end + 1 < lines.size && end - start < 4 &&
                (scores[end + 1] >= 1 || looksLikeAddressContinuation(lines[end + 1])) &&
                !isJunk(lines[end + 1])) {
                if (anchorHasStreet && plusCode.containsMatchIn(lines[end + 1])) break
                end++
            }
            val score = (start..end).sumOf { scores[it] } + (end - start)
            if (best == null || score > best!!.third) best = Triple(start, end, score)
        }
        val winner = best ?: return null
        val window = (winner.first..winner.second).map { lines[it] }
        val hasStructuredStreet = window.any { street.containsMatchIn(it) }
        val address = window.filterNot { hasStructuredStreet && plusCode.containsMatchIn(it) }
            .map { it.replace(addressPrefix, "") }.distinct().joinToString(", ")
        val value = address.takeIf { it.length >= 5 } ?: return null
        return winner.first to value
    }

    private fun looksLikeAddressContinuation(line: String): Boolean =
        line.length in 2..100 && line.any(Char::isLetter) &&
            !line.contains(Regex("""[.!?]\s+\p{Lu}"""))

    private fun addressScore(line: String): Int {
        if (isJunk(line)) return -100
        var score = 0
        if (street.containsMatchIn(line)) score += 7
        if (administration.containsMatchIn(line)) score += 4
        if (addressPrefix.containsMatchIn(line)) score += 5
        if (postalCode.containsMatchIn(line)) score += 4
        if (plusCode.containsMatchIn(line)) score += 6
        if (country.containsMatchIn(line)) score += 3
        if (line.contains(',')) score += 1
        if (Regex("""^\d{1,6}\s+\p{L}""").containsMatchIn(line)) score += 2
        if (Regex("""\b(?:building|tower|floor|suite|unit|block|lot|no\.?|number)\s*[A-Z0-9-]+\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)) score += 2
        return score
    }

    private fun isJunk(line: String): Boolean {
        val trimmed = line.trim().trim('•', '·', '|')
        return junk.matches(trimmed) || sentenceNoise.containsMatchIn(trimmed) ||
            phone.matches(trimmed) || rating.matches(trimmed) ||
            trimmed.contains(Regex("""(?i)\b(?:KB/s|MB/s|5G|VoLTE|battery)\b""")) ||
            trimmed.length > 180
    }

    private fun findPlaceName(lines: List<String>, addressStart: Int): String? = lines.take(addressStart)
        .mapIndexedNotNull { index, line ->
            if (isJunk(line) || addressScore(line) > 0 || line.length !in 2..70 ||
                line.endsWith("...") || line.endsWith('…') || !line.first().isUpperCase()) return@mapIndexedNotNull null
            var score = if (index < 5) 3 else 0
            if (line.contains(Regex("""(?i)\b(?:coffee|cafe|café|restaurant|hotel|school|office|clinic|hospital|store|shop|mall|gym|church|mosque|temple|university|airport|station|park)\b"""))) score += 4
            if (line.contains('&') || line.contains('\'')) score += 1
            Triple(line, score, index)
        }.maxWithOrNull(compareBy<Triple<String, Int, Int>> { it.second }.thenBy { -it.third })?.first
}
