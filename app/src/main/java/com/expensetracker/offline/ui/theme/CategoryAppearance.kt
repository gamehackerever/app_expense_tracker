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
        Color(0xFF00E676), Icons.Default.FitnessCenter, // Electric Neon Green
        setOf("gym", "fitness", "workout", "creatine", "protein", "whey", "sports", "sport", "turf", "badminton", "swimming", "yoga", "cult")
    ),
    AUDIO_STREAMING(
        Color(0xFF00E676), Icons.Default.Headphones, // Vivid Green
        setOf("spotify", "music", "podcast", "audible", "gaana", "wynk", "saavn", "jiosaavn")
    ),
    CLOUD_DIGITAL(
        Color(0xFF00B0FF), Icons.Default.Cloud, // Electric Sky Blue
        setOf("photos", "icloud", "cloud", "drive", "storage", "chatgpt", "openai", "claude", "github", "notion", "canva")
    ),
    TELECOM(
        Color(0xFF00E5FF), Icons.Default.PhoneAndroid, // Bright Cyan
        setOf("recharge", "telecom", "vi", "jio", "airtel", "bsnl", "vodafone", "prepaid", "postpaid", "sim", "mobile", "dth", "tataplay", "fiber", "broadband")
    ),
    CHAI_CAFE(
        Color(0xFFFF9100), Icons.Default.LocalCafe, // Warm Golden Amber
        setOf("chai", "tea", "coffee", "cafe", "chaayos", "starbucks", "bakery", "bakers")
    ),
    FOOD(
        Color(0xFFFF6D00), Icons.Default.Fastfood, // Vivid Tangerine Orange
        setOf("food", "dining", "restaurant", "restaurants", "snack", "snacks", "meal", "meals",
            "lunch", "dinner", "breakfast", "zomato", "swiggy", "broast", "shawarma", "biryani", "mandhi", "canteen", "mess", "dosa", "tiffin", "thali", "sweets", "mithai", "puff", "puffs")
    ),
    GROCERIES(
        Color(0xFFFF1744), Icons.Default.ShoppingCart, // Vivid Primary Red
        setOf("groceries", "grocery", "vegetables", "fruits", "supermarket", "mart", "kirana", "milk", "milma", "nandini", "amul", "blinkit", "zepto", "instamart", "bigbasket", "dmart", "sabzi", "mandi", "dairy")
    ),
    FUEL_FASTAG(
        Color(0xFFFF3D00), Icons.Default.LocalGasStation, // Vivid Red-Orange Flame
        setOf("fuel", "fastag", "petrol", "diesel", "cng", "hpcl", "bpcl", "iocl", "shell", "nayara", "toll", "nhai", "parking")
    ),
    TRAIN_METRO(
        Color(0xFF2979FF), Icons.Default.Train, // Royal Electric Blue
        setOf("train", "irctc", "metro", "railway", "rail", "dmrc", "bmrcl", "kmrl", "cmrl")
    ),
    FLIGHT(
        Color(0xFF00B0FF), Icons.Default.Flight, // Bright Azure Blue
        setOf("flight", "flights", "airline", "indigo", "airindia", "vistara", "akasa", "airport")
    ),
    TRANSPORT(
        Color(0xFF2979FF), Icons.Default.DirectionsCar, // Electric Cobalt Blue
        setOf("travel", "transport", "transportation", "cab", "cabs", "taxi", "auto", "uber", "ola", "rapido",
            "bus", "commute", "ride", "rides", "ksrtc", "redbus")
    ),
    SUBSCRIPTIONS(
        Color(0xFF00B0FF), Icons.Default.Subscriptions, // Electric Sky Blue
        setOf("subscriptions", "subscription", "ott", "netflix", "hotstar", "prime", "youtube", "sonyliv", "zee5", "mandate", "autopay")
    ),
    ENTERTAINMENT(
        Color(0xFFFF1744), Icons.Default.Movie, // Vivid Crimson Red
        setOf("entertainment", "movie", "movies", "games", "gaming", "pvr", "inox", "bookmyshow", "cinema", "concert", "wonderla")
    ),
    EMI_LOANS(
        Color(0xFFFF9100), Icons.Default.CreditScore, // Vivid Amber Gold
        setOf("emi", "loans", "loan", "bajaj", "muthoot", "manappuram", "repayment", "kreditbee", "lazypay", "simpl", "slice", "cred", "nach")
    ),
    RENT_HOUSING(
        Color(0xFF2979FF), Icons.Default.Home, // Royal Blue
        setOf("rent", "housing", "home", "society", "maintenance", "furniture", "hostel", "pg", "flat", "nobroker", "mygate", "landlord")
    ),
    HOUSEHOLD_SERVICES(
        Color(0xFF00C853), Icons.Default.CleaningServices, // Vivid Green
        setOf("household", "services", "maid", "cook", "laundry", "dryclean", "cleaning", "plumber", "electrician", "carpenter", "urban", "lpg", "cylinder", "indane", "bharatgas", "bisleri")
    ),
    BILLS(
        Color(0xFF00B4D8), Icons.AutoMirrored.Filled.ReceiptLong, // Bright Azure Blue
        setOf("bills", "bill", "utilities", "utility", "electricity", "internet", "wifi",
            "insurance", "kseb", "bescom", "tneb", "msedcl", "water", "gas", "bbps")
    ),
    PERSONAL_CARE(
        Color(0xFFFF6D00), Icons.Default.Spa, // Vivid Orange
        setOf("personal", "care", "salon", "spa", "parlour", "barber", "haircut", "grooming", "nykaa", "purplle", "cosmetics", "skincare")
    ),
    SHOPPING(
        Color(0xFF2979FF), Icons.Default.ShoppingBag, // Vibrant Royal Blue
        setOf("shopping", "clothes", "clothing", "fashion", "electronics", "amazon", "flipkart", "myntra", "ajio", "zudio", "meesho", "croma", "decathlon", "tanishq", "jewellery")
    ),
    GIFTS_FESTIVALS(
        Color(0xFFFF1744), Icons.Default.VolunteerActivism, // Vivid Red
        setOf("gifts", "gift", "festivals", "festival", "puja", "pooja", "temple", "mandir", "donation", "charity", "shagun", "dakshina", "zakat", "tithe", "fnp")
    ),
    TRANSFER(
        Color(0xFF00B0FF), Icons.AutoMirrored.Filled.CompareArrows, // Bright Sky Blue
        setOf("transfer", "transfers", "self", "sweep", "withdrawal", "atm")
    ),
    BANK(
        Color(0xFF2979FF), Icons.Default.AccountBalance, // Royal Blue
        setOf("bank", "charges", "charge", "fee", "fees", "interest", "tax", "taxes", "gst", "penalty")
    ),
    HEALTH(
        Color(0xFFFF1744), Icons.Default.LocalHospital, // Crisp Luminous Red
        setOf("health", "healthcare", "medical", "medicine", "medicines", "pharmacy", "hospital", "doctor", "clinic", "dental", "apollo", "medplus", "pharmeasy", "1mg", "pathology", "diagnostics")
    ),
    EDUCATION(
        Color(0xFF00C853), Icons.Default.School, // Vivid Green
        setOf("education", "school", "tuition", "course", "courses", "books", "study", "college", "exam", "coaching", "physicswallah", "allen", "unacademy", "stationery")
    ),
    INVESTMENTS(
        Color(0xFF00C853), Icons.AutoMirrored.Filled.TrendingUp, // Vibrant Green
        setOf("investment", "investments", "mutual", "fund", "funds", "sip", "stocks", "stock", "zerodha", "groww", "upstox", "indmoney", "dhan", "angel", "lic", "nps", "ppf", "gold")
    ),
    UNSPECIFIED(
        Color(0xFF00B0FF), Icons.Default.Category, // Bright Azure Blue
        setOf("unspecified", "uncategorized", "unclassified")
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
    Color(0xFF2979FF), // Vibrant Royal Blue
    Color(0xFFFF6D00), // Vibrant Tangerine Orange
    Color(0xFF00C853), // Vibrant Green
    Color(0xFFFF1744), // Vivid Crimson Red
    Color(0xFF00B0FF), // Bright Azure Blue
    Color(0xFFFF9100), // Amber Gold Orange
    Color(0xFF00E676), // Electric Mint Green
    Color(0xFFD50000)  // Vivid Deep Red
)

fun getCategoryIcon(category: String): ImageVector = kindOf(category).icon

/** False for custom/unrecognised categories that would otherwise show the generic fallback icon. */
fun hasCategoryIcon(category: String): Boolean = kindOf(category) != CategoryKind.OTHER
