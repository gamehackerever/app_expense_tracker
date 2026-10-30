package com.expensetracker.offline.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CreditScore
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Categories and bill names are matched by whole words so Indian & global categories
 * ("Fuel & Fastag", "Recharge & Telecom", "Rent & Housing", "Household & Services",
 * "EMI & Loans", "Subscriptions", "Personal Care", "Gifts & Festivals", "Food & Dining",
 * "Groceries", "Travel", etc.) and specific necessity names ("Chai", "Metro", "KSEB",
 * "VI Recharge", "Gym", "Creatine") resolve to crisp, distinct icons and vibrant semantic colors.
 */
private enum class CategoryKind(val color: Color?, val icon: ImageVector, val keywords: Set<String>) {
    FITNESS(
        Color(0xFF22C55E), Icons.Default.FitnessCenter, // Energetic Green
        setOf("gym", "fitness", "workout", "creatine", "protein", "whey", "sports", "sport", "turf", "badminton", "swimming", "yoga", "cult")
    ),
    AUDIO_STREAMING(
        Color(0xFF1DB954), Icons.Default.Headphones, // Spotify Green
        setOf("spotify", "music", "podcast", "audible", "gaana", "wynk", "saavn", "jiosaavn")
    ),
    CLOUD_DIGITAL(
        Color(0xFF38BDF8), Icons.Default.Cloud, // Sky Blue
        setOf("photos", "icloud", "cloud", "drive", "storage", "chatgpt", "openai", "claude", "github", "notion", "canva")
    ),
    TELECOM(
        Color(0xFF06B6D4), Icons.Default.PhoneAndroid, // Cyan
        setOf("recharge", "telecom", "vi", "jio", "airtel", "bsnl", "vodafone", "prepaid", "postpaid", "sim", "mobile", "dth", "tataplay", "fiber", "broadband")
    ),
    CHAI_CAFE(
        Color(0xFFD97706), Icons.Default.LocalCafe, // Warm Chai Amber
        setOf("chai", "tea", "coffee", "cafe", "chaayos", "starbucks", "bakery", "bakers")
    ),
    FOOD(
        Color(0xFFF59E0B), Icons.Default.Fastfood, // Vibrant Amber
        setOf("food", "dining", "restaurant", "restaurants", "snack", "snacks", "meal", "meals",
            "lunch", "dinner", "breakfast", "zomato", "swiggy", "broast", "shawarma", "biryani", "mandhi", "canteen", "mess", "dosa", "tiffin", "thali", "sweets", "mithai")
    ),
    GROCERIES(
        Color(0xFF10B981), Icons.Default.ShoppingCart, // Emerald
        setOf("groceries", "grocery", "vegetables", "fruits", "supermarket", "mart", "kirana", "milk", "milma", "nandini", "amul", "blinkit", "zepto", "instamart", "bigbasket", "dmart", "sabzi", "mandi", "dairy")
    ),
    FUEL_FASTAG(
        Color(0xFFF97316), Icons.Default.LocalGasStation, // Vibrant Orange
        setOf("fuel", "fastag", "petrol", "diesel", "cng", "hpcl", "bpcl", "iocl", "shell", "nayara", "toll", "nhai", "parking")
    ),
    TRAIN_METRO(
        Color(0xFF2563EB), Icons.Default.Train, // Railway Blue
        setOf("train", "irctc", "metro", "railway", "rail", "dmrc", "bmrcl", "kmrl", "cmrl")
    ),
    FLIGHT(
        Color(0xFF0284C7), Icons.Default.Flight, // Sky Blue
        setOf("flight", "flights", "airline", "indigo", "airindia", "vistara", "akasa", "airport")
    ),
    TRANSPORT(
        Color(0xFF3B82F6), Icons.Default.DirectionsCar, // Bright Blue
        setOf("travel", "transport", "transportation", "cab", "cabs", "taxi", "auto", "uber", "ola", "rapido",
            "bus", "commute", "ride", "rides", "ksrtc", "redbus")
    ),
    SUBSCRIPTIONS(
        Color(0xFFA855F7), Icons.Default.Subscriptions, // Rich Purple
        setOf("subscriptions", "subscription", "ott", "netflix", "hotstar", "prime", "youtube", "sonyliv", "zee5", "mandate", "autopay")
    ),
    ENTERTAINMENT(
        Color(0xFFEC4899), Icons.Default.Movie, // Pink
        setOf("entertainment", "movie", "movies", "games", "gaming", "pvr", "inox", "bookmyshow", "cinema", "concert", "wonderla")
    ),
    EMI_LOANS(
        Color(0xFFEA580C), Icons.Default.CreditScore, // Deep Amber-Orange
        setOf("emi", "loans", "loan", "bajaj", "muthoot", "manappuram", "repayment", "kreditbee", "lazypay", "simpl", "slice", "cred", "nach")
    ),
    RENT_HOUSING(
        Color(0xFF6366F1), Icons.Default.Home, // Indigo
        setOf("rent", "housing", "home", "society", "maintenance", "furniture", "hostel", "pg", "flat", "nobroker", "mygate", "landlord")
    ),
    HOUSEHOLD_SERVICES(
        Color(0xFF0D9488), Icons.Default.CleaningServices, // Deep Teal
        setOf("household", "services", "maid", "cook", "laundry", "dryclean", "cleaning", "plumber", "electrician", "carpenter", "urban", "lpg", "cylinder", "indane", "bharatgas", "bisleri")
    ),
    BILLS(
        Color(0xFF0891B2), Icons.AutoMirrored.Filled.ReceiptLong, // Cyan-Blue
        setOf("bills", "bill", "utilities", "utility", "electricity", "internet", "wifi",
            "insurance", "kseb", "bescom", "tneb", "msedcl", "water", "gas", "bbps")
    ),
    PERSONAL_CARE(
        Color(0xFFF43F5E), Icons.Default.Spa, // Rose
        setOf("personal", "care", "salon", "spa", "parlour", "barber", "haircut", "grooming", "nykaa", "purplle", "cosmetics", "skincare")
    ),
    SHOPPING(
        Color(0xFF8B5CF6), Icons.Default.ShoppingBag, // Violet
        setOf("shopping", "clothes", "clothing", "fashion", "electronics", "amazon", "flipkart", "myntra", "ajio", "zudio", "meesho", "croma", "decathlon", "tanishq", "jewellery")
    ),
    GIFTS_FESTIVALS(
        Color(0xFFE11D48), Icons.Default.VolunteerActivism, // Festive Crimson-Rose
        setOf("gifts", "gift", "festivals", "festival", "puja", "pooja", "temple", "mandir", "donation", "charity", "shagun", "dakshina", "zakat", "tithe", "fnp")
    ),
    TRANSFER(
        Color(0xFF64748B), Icons.AutoMirrored.Filled.CompareArrows, // Slate
        setOf("transfer", "transfers", "self", "sweep", "withdrawal", "atm")
    ),
    BANK(
        Color(0xFF64748B), Icons.Default.AccountBalance, // Slate
        setOf("bank", "charges", "charge", "fee", "fees", "interest", "tax", "taxes", "gst", "penalty")
    ),
    HEALTH(
        Color(0xFFEF4444), Icons.Default.LocalHospital, // Crisp Red
        setOf("health", "healthcare", "medical", "medicine", "medicines", "pharmacy", "hospital", "doctor", "clinic", "dental", "apollo", "medplus", "pharmeasy", "1mg", "pathology", "diagnostics")
    ),
    EDUCATION(
        Color(0xFF84CC16), Icons.Default.School, // Lime
        setOf("education", "school", "tuition", "course", "courses", "books", "study", "college", "exam", "coaching", "physicswallah", "allen", "unacademy", "stationery")
    ),
    INVESTMENTS(
        Color(0xFF14B8A6), Icons.AutoMirrored.Filled.TrendingUp, // Teal
        setOf("investment", "investments", "mutual", "fund", "funds", "sip", "stocks", "stock", "zerodha", "groww", "upstox", "indmoney", "dhan", "angel", "lic", "nps", "ppf", "gold")
    ),
    OTHER(null, Icons.Default.Category, emptySet())
}

private val wordSplitter = Regex("[^a-z0-9]+")
private val kindCache = ConcurrentHashMap<String, CategoryKind>()

private fun kindOf(category: String): CategoryKind = kindCache.getOrPut(category) {
    val words = category.lowercase().split(wordSplitter).filter { it.isNotEmpty() }.toSet()
    CategoryKind.entries.firstOrNull { kind -> kind.keywords.any { it in words } } ?: CategoryKind.OTHER
}

fun getCategoryColor(category: String): Color =
    kindOf(category).color ?: customPalette[abs(category.hashCode()) % customPalette.size]

private val customPalette = listOf(
    Color(0xFF14B8A6), // Teal
    Color(0xFF8B5CF6), // Violet
    Color(0xFF3B82F6), // Blue
    Color(0xFFF43F5E), // Rose
    Color(0xFF0EA5E9), // Sky
    Color(0xFFF97316)  // Orange
)

fun getCategoryIcon(category: String): ImageVector = kindOf(category).icon

/** False for custom/unrecognised categories that would otherwise show the generic fallback icon. */
fun hasCategoryIcon(category: String): Boolean = kindOf(category) != CategoryKind.OTHER
