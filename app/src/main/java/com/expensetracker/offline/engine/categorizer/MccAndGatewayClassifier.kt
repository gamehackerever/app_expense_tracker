package com.expensetracker.offline.engine.categorizer

import java.util.Locale
import java.util.regex.Pattern

/**
 * Intelligent classifier for Merchant Category Codes (ISO 18245 MCC) and
 * commercial Payment Gateway signatures (Razorpay, BillDesk, PayU, Pine Labs, etc.).
 *
 * Provides deterministic classification when card POS terminals, bank statements,
 * or UPI merchant gateway signatures are present in the transaction narration.
 */
object MccAndGatewayClassifier {

    data class MccMatch(
        val category: String,
        val mccCode: String,
        val description: String
    )

    // Regex to detect explicit MCC codes in statement text or card narrations:
    // Examples: "MCC: 5812", "MCC 5411", "MCC-7832", "MCC/5541", "MCC:8299"
    private val MCC_PATTERN = Pattern.compile("(?i)\\bMCC[:/\\-\\s]+(\\d{4})\\b")

    // ISO 18245 Merchant Category Codes mapping
    private val MCC_TO_CATEGORY: Map<String, Pair<String, String>> = mapOf(
        // Food & Dining (5811 - 5814)
        "5812" to ("Food & Dining" to "Eating Places and Restaurants"),
        "5814" to ("Food & Dining" to "Fast Food Restaurants"),
        "5813" to ("Food & Dining" to "Drinking Places, Bars, Taverns"),
        "5811" to ("Food & Dining" to "Caterers"),
        "5462" to ("Food & Dining" to "Bakeries"),

        // Groceries (5411, 5422, 5499, etc.)
        "5411" to ("Groceries" to "Grocery Stores and Supermarkets"),
        "5422" to ("Groceries" to "Freezer and Meat Lockers"),
        "5441" to ("Groceries" to "Candy, Nut, Confectionery Stores"),
        "5451" to ("Groceries" to "Dairy Products Stores"),
        "5499" to ("Groceries" to "Misc Food Stores, Convenience, Markets"),

        // Fuel & Fastag (5541, 5542, 5552)
        "5541" to ("Fuel & Fastag" to "Service Stations, Fuel Dispensing"),
        "5542" to ("Fuel & Fastag" to "Automated Fuel Dispensers"),
        "5552" to ("Fuel & Fastag" to "Electric Vehicle Charging"),
        "7523" to ("Fuel & Fastag" to "Automobile Parking Lots and Garages"),
        "4784" to ("Fuel & Fastag" to "Tolls and Bridge Fees"),

        // Travel & Transportation (4000 - 4799)
        "4111" to ("Travel" to "Local and Suburban Commuter Passenger Transport"),
        "4112" to ("Travel" to "Passenger Railways"),
        "4121" to ("Travel" to "Taxicabs and Limousines"),
        "4131" to ("Travel" to "Bus Lines"),
        "4511" to ("Travel" to "Airlines and Air Carriers"),
        "4722" to ("Travel" to "Travel Agencies and Tour Operators"),
        "7011" to ("Travel" to "Hotels, Motels, and Resorts"),
        "7512" to ("Travel" to "Automobile Rental Agency"),

        // Recharge & Telecom (4812, 4814, 4899)
        "4812" to ("Recharge & Telecom" to "Telecommunication Equipment and Telephone Sales"),
        "4814" to ("Recharge & Telecom" to "Telecommunication Services"),
        "4899" to ("Recharge & Telecom" to "Cable and Other Pay Television Services"),

        // Bills & Utilities (4900)
        "4900" to ("Bills & Utilities" to "Utilities - Electric, Gas, Water, Sanitary"),

        // Rent & Housing (6513)
        "6513" to ("Rent & Housing" to "Real Estate Agents, Managers, Rentals"),

        // Entertainment (7832, 7922, 7996, 7999, etc.)
        "7832" to ("Entertainment" to "Motion Picture Theaters"),
        "7829" to ("Entertainment" to "Motion Picture and Video Tape Production"),
        "7922" to ("Entertainment" to "Theatrical Producers and Ticket Agencies"),
        "7996" to ("Entertainment" to "Amusement Parks, Carnivals, Circuses"),
        "7997" to ("Entertainment" to "Membership Clubs, Country Clubs, Golf"),
        "7999" to ("Entertainment" to "Recreation Services, Arcades, Bowling"),
        "7932" to ("Entertainment" to "Billiard and Pool Establishments"),
        "7933" to ("Entertainment" to "Bowling Alleys"),

        // Health & Medical (8011 - 8099, 5912)
        "5912" to ("Health" to "Drug Stores and Pharmacies"),
        "8011" to ("Health" to "Doctors and Physicians"),
        "8021" to ("Health" to "Dentists and Orthodontists"),
        "8042" to ("Health" to "Optometrists and Ophthalmologists"),
        "8062" to ("Health" to "General Hospitals"),
        "8071" to ("Health" to "Medical and Dental Laboratories"),
        "8099" to ("Health" to "Medical Services and Health Practitioners"),

        // Personal Care (7230, 7298)
        "7230" to ("Personal Care" to "Barber and Beauty Shops"),
        "7298" to ("Personal Care" to "Health and Beauty Spas"),

        // Education & Stationery (8211 - 8299, 5942, 5943)
        "8211" to ("Education" to "Elementary and Secondary Schools"),
        "8220" to ("Education" to "Colleges, Universities, Professional Schools"),
        "8241" to ("Education" to "Correspondence Schools"),
        "8244" to ("Education" to "Business and Secretarial Schools"),
        "8249" to ("Education" to "Vocational and Trade Schools"),
        "8299" to ("Education" to "Educational Services - Schools & Coaching"),
        "5942" to ("Education" to "Book Stores"),
        "5943" to ("Education" to "Stationery, Office Supplies, and Printing"),

        // Investments & Securities (6211, 6012)
        "6211" to ("Investments" to "Security Brokers and Dealers"),
        "6012" to ("Investments" to "Financial Institutions - Merchandise and Services"),

        // Shopping (5311, 5651, 5732, etc.)
        "5311" to ("Shopping" to "Department Stores"),
        "5611" to ("Shopping" to "Men's and Boys' Clothing"),
        "5621" to ("Shopping" to "Women's Ready-to-Wear Stores"),
        "5651" to ("Shopping" to "Family Clothing Stores"),
        "5661" to ("Shopping" to "Shoe Stores"),
        "5732" to ("Shopping" to "Electronics Stores"),
        "5944" to ("Shopping" to "Jewelry Stores, Watches, Silverware"),
        "5948" to ("Shopping" to "Luggage and Leather Goods Stores"),
        "5712" to ("Shopping" to "Furniture and Home Furnishings Stores"),

        // Gifts & Festivals (8398, 8661)
        "5992" to ("Gifts & Festivals" to "Florists, Bouquets"),
        "8398" to ("Gifts & Festivals" to "Charitable and Social Service Organizations"),
        "8661" to ("Gifts & Festivals" to "Religious Organizations, Temples, Churches")
    )

    // Known payment gateway VPA domains used exclusively for merchant checkouts
    private val GATEWAY_DOMAINS = setOf(
        "razorpay", "billdesk", "payu", "ccavenue", "pinelabs",
        "cashfree", "paytmmerchant", "icicimerchant", "hdfcmerchant",
        "sbiupi", "axismerchant"
    )

    /**
     * Inspects raw statement text for ISO 18245 MCC codes.
     */
    fun extractMccMatch(text: String): MccMatch? {
        val matcher = MCC_PATTERN.matcher(text)
        if (matcher.find()) {
            val code = matcher.group(1) ?: return null
            val mapping = MCC_TO_CATEGORY[code]
            if (mapping != null) {
                return MccMatch(
                    category = mapping.first,
                    mccCode = code,
                    description = mapping.second
                )
            }
        }
        return null
    }

    /**
     * Inspects UPI handles (VPAs) for commercial payment gateway indicators.
     * Extracts the clean merchant handle embedded before the gateway domain.
     *
     * Example: "bookmyshow@razorpay" -> returns Pair("bookmyshow", true)
     */
    fun parseGatewayVpa(vpa: String): Pair<String, Boolean>? {
        val clean = vpa.trim().lowercase(Locale.ROOT)
        if (!clean.contains("@")) return null
        val parts = clean.split("@")
        if (parts.size != 2) return null
        val handle = parts[0]
        val domain = parts[1]

        val isGateway = GATEWAY_DOMAINS.any { domain.contains(it) }
        return Pair(handle, isGateway)
    }
}
