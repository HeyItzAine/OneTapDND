package com.example.onetapdnd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressOcrParserTest {
    @Test
    fun extractsAddressAndNameFromNoisyMapsScreenshot() {
        val text = """
            19.17     8.01 KB/s  VoLTE  5G  45
            WJ's Coffee & Eatery
            Overview Menu Reviews Photos Updates
            Some say the food preparation process can be slow during busy times
            Order online
            Open · Closes 22.00
            Jl. Legenda Wisata, Wanaherang,
            Kec. Gn. Putri, Kabupaten Bogor,
            Jawa Barat 16967
            JW2W+6Q Wanaherang, Bogor Regency, West Java
            Update location
            Popular times
            Mondays
            Live 7 pm
            Less busy than usual
            Ask Order Call
        """.trimIndent()

        val result = AddressOcrParser.parse(text)

        assertEquals("WJ's Coffee & Eatery", result.placeName)
        assertTrue(result.address.orEmpty().contains("Jl. Legenda Wisata"))
        assertTrue(result.address.orEmpty().contains("Jawa Barat 16967"))
        assertFalse(result.address.orEmpty().contains("Popular times"))
        assertFalse(result.address.orEmpty().contains("JW2W+6Q"))
    }

    @Test
    fun extractsInternationalAddressFormats() {
        val cases = listOf(
            "The White House\n1600 Pennsylvania Avenue NW, Washington, DC 20500, USA" to "Pennsylvania Avenue",
            "Empire State Building\n20 W 34th Street, New York, NY 10001" to "34th Street",
            "Golden Gate Park\n501 Stanyan St, San Francisco, CA 94117" to "Stanyan St",
            "Buckingham Palace\nLondon SW1A 1AA, United Kingdom" to "SW1A 1AA",
            "Tower Bridge\nTower Bridge Road, London SE1 2UP, UK" to "Bridge Road",
            "CN Tower\n290 Bremner Blvd, Toronto, ON M5V 3L9, Canada" to "Bremner Blvd",
            "Sydney Opera House\nBennelong Point, Sydney NSW 2000, Australia" to "Sydney NSW 2000",
            "Eiffel Tower\n5 Avenue Anatole France, 75007 Paris, France" to "Avenue Anatole France",
            "Louvre Museum\nRue de Rivoli, 75001 Paris, France" to "Rue de Rivoli",
            "Colosseum\nPiazza del Colosseo, 1, 00184 Roma RM, Italy" to "00184",
            "Sagrada Familia\nCarrer de Mallorca, 401, 08013 Barcelona, Spain" to "08013",
            "Brandenburg Gate\nPariser Platz, 10117 Berlin, Germany" to "10117",
            "Rijksmuseum\nMuseumstraat 1, 1071 XX Amsterdam, Netherlands" to "1071 XX",
            "Belém Tower\nAvenida Brasília, 1400-038 Lisboa" to "Avenida Brasília",
            "Christ the Redeemer\nRua Cosme Velho, 513, Rio de Janeiro, Brazil" to "Rua Cosme Velho",
            "Palacio de Bellas Artes\nAvenida Juárez, Centro Histórico, 06050 Ciudad de México, Mexico" to "06050",
            "Marina Bay Sands\n10 Bayfront Avenue, Singapore 018956" to "018956",
            "Petronas Towers\nKuala Lumpur City Centre, 50088 Kuala Lumpur, Malaysia" to "50088",
            "India Gate\nKartavya Path, New Delhi, Delhi 110001, India" to "110001",
            "Tokyo Tower\n4 Chome-2-8 Shibakoen, Minato City, Tokyo 105-0011, Japan" to "105-0011",
            "Auckland Museum\nThe Auckland Domain, Parnell, Auckland 1010, New Zealand" to "Auckland 1010",
            "Table Mountain\nTafelberg Road, Cape Town, 8001, South Africa" to "Tafelberg Road",
            "Grand Palace\nNa Phra Lan Road, Phra Nakhon, Bangkok 10200, Thailand" to "10200",
            "Rizal Park\nRoxas Boulevard, Ermita, Manila 1000, Philippines" to "Roxas Boulevard",
            "Ho Chi Minh City Hall\n86 Le Thanh Ton Street, District 1, Vietnam" to "District 1",
            "Trinity College\nCollege Green, Dublin 2, Ireland" to "Ireland",
            "Vienna State Opera\nOpernring 2, 1010 Wien, Austria" to "1010",
            "Chapel Bridge\nKapellbrücke, 6002 Luzern, Switzerland" to "6002",
            "Atomium\nPlace de l'Atomium 1, 1020 Bruxelles, Belgium" to "1020",
            "Monas\nJl. Medan Merdeka Barat, Gambir, Jakarta 10110, Indonesia" to "Jl. Medan Merdeka Barat"
        )

        cases.forEach { (input, expectedPart) ->
            val actual = AddressOcrParser.parse(input).address.orEmpty()
            assertTrue("Expected '$expectedPart' in '$actual'", actual.contains(expectedPart, ignoreCase = true))
        }
    }

    @Test
    fun acceptsAddressLabelsAndUnitDetails() {
        val cases = listOf(
            "Address: 1 Infinite Loop, Cupertino, CA 95014" to "1 Infinite Loop",
            "Alamat: Jl. Asia Afrika No. 65, Bandung, Jawa Barat 40111" to "Jl. Asia Afrika",
            "Location: 350 Fifth Avenue, Floor 80, New York, NY 10118" to "Floor 80",
            "Adresse: Rue du Marché 12, 1204 Genève, Switzerland" to "Rue du Marché",
            "Dirección: Calle de Alcalá 42, 28014 Madrid, Spain" to "Calle de Alcalá",
            "Endereço: Avenida Paulista 1578, São Paulo 01310-200, Brazil" to "Avenida Paulista"
        )
        cases.forEach { (input, expectedPart) ->
            assertTrue(AddressOcrParser.parse(input).address.orEmpty().contains(expectedPart, ignoreCase = true))
        }
    }

    @Test
    fun extractsCoordinateFormats() {
        val cases = listOf(
            "Coordinates: -6.301234, 106.953456" to (-6.301234 to 106.953456),
            "-33.8567844, 151.2152967" to (-33.8567844 to 151.2152967),
            "51.500729; -0.124625" to (51.500729 to -0.124625),
            "https://maps.google.com/maps/@35.658581,139.745438,17z" to (35.658581 to 139.745438),
            "https://maps.google.com/?q=-22.951916%2C-43.210487" to (-22.951916 to -43.210487),
            "https://maps.google.com/?query=48.858370,2.294481" to (48.858370 to 2.294481),
            "6.3012° S, 106.9534° E" to (-6.3012 to 106.9534),
            "33.8568 S 151.2153 E" to (-33.8568 to 151.2153),
            "40.6892 N, 74.0445 W" to (40.6892 to -74.0445),
            "Latitude: -6,3012 Longitude: 106,9534" to (-6.3012 to 106.9534),
            "lat=0 lng=0" to (0.0 to 0.0),
            "GPS 90.0, 180.0" to (90.0 to 180.0),
            "GPS -90.0, -180.0" to (-90.0 to -180.0)
        )
        cases.forEach { (input, expected) ->
            val result = AddressOcrParser.parse(input)
            assertEquals("latitude for $input", expected.first, result.latitude!!, 0.000001)
            assertEquals("longitude for $input", expected.second, result.longitude!!, 0.000001)
        }
    }

    @Test
    fun rejectsInvalidCoordinatesAndUiNumbers() {
        val cases = listOf(
            "Coordinates: 91.0, 20.0",
            "Coordinates: -91.0, 20.0",
            "Coordinates: 45.0, 181.0",
            "Coordinates: 45.0, -181.0",
            "19.17 8.01 KB/s VoLTE 5G 45",
            "Open · Closes 22.00",
            "4.8 (1,501)",
            "Live 7 pm",
            "100, 200"
        )
        cases.forEach { input ->
            val result = AddressOcrParser.parse(input)
            assertNull("latitude for $input", result.latitude)
            assertNull("longitude for $input", result.longitude)
        }
    }

    @Test
    fun rejectsMapsControlsReviewsAndPhoneNoiseAsAddresses() {
        val cases = listOf(
            "Overview\nMenu\nReviews\nPhotos\nUpdates",
            "Order online\nAsk\nOrder\nCall\nShare",
            "Open · Closes 22.00\nPopular times\nMondays\nLive 7 pm",
            "Some say the food preparation process can be slow during busy times",
            "Directions\nSave\nNearby\nSend to phone",
            "Website\nAccessibility\nPayments\nServices",
            "+1 555-0100",
            "0852-0000-0000",
            "4.7 stars",
            ""
        )
        cases.forEach { input -> assertNull("address for $input", AddressOcrParser.parse(input).address) }
    }

    @Test
    fun acceptsPlusCodeWhenItIsTheOnlyLocation() {
        val result = AddressOcrParser.parse("JW2W+6Q Wanaherang, Bogor Regency, West Java")
        assertTrue(result.address.orEmpty().contains("JW2W+6Q"))
        assertTrue(result.address.orEmpty().contains("Bogor Regency"))
    }

    @Test
    fun boundsVeryLargeOcrInput() {
        val oversized = buildString {
            repeat(300) { appendLine("Overview Menu Reviews Photos Updates ${"x".repeat(400)}") }
            append("1600 Pennsylvania Avenue NW, Washington, DC 20500, USA")
        }
        assertNull(AddressOcrParser.parse(oversized).address)
    }
}
