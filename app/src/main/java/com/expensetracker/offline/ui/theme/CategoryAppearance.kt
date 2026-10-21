package com.expensetracker.offline.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Categories and bill names are matched by whole words so "Auto/Cab", "Cab rides", "Spotify",
 * "Google Photos", "VI Recharge", "Gym", and "Creatine" all resolve to crisp, distinct icons
 * and vibrant semantic colors across both Light and Dark themes.
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
        setOf("vi", "jio", "airtel", "bsnl", "vodafone", "recharge", "prepaid", "postpaid", "sim", "mobile")
    ),
    FOOD(
        Color(0xFFF59E0B), Icons.Default.Fastfood, // Vibrant Amber
        setOf("food", "dining", "restaurant", "restaurants", "cafe", "coffee", "snack", "snacks", "meal", "meals",
            "lunch", "dinner", "breakfast", "zomato", "swiggy", "broast", "shawarma", "biryani", "mandhi", "canteen", "mess", "bakery", "tea", "chai")
    ),
    GROCERIES(
        Color(0xFF10B981), Icons.Default.ShoppingCart, // Emerald
        setOf("groceries", "grocery", "vegetables", "fruits", "supermarket", "mart", "kirana", "milk", "milma", "blinkit", "zepto", "instamart", "bigbasket", "dmart")
    ),
    SHOPPING(
        Color(0xFF8B5CF6), Icons.Default.ShoppingBag, // Violet
        setOf("shopping", "clothes", "clothing", "fashion", "electronics", "amazon", "flipkart", "myntra", "ajio", "zudio", "meesho")
    ),
    FUEL(
        Color(0xFFF97316), Icons.Default.LocalGasStation, // Orange
        setOf("fuel", "petrol", "diesel", "gas", "hpcl", "bpcl", "iocl")
    ),
    TRANSPORT(
        Color(0xFF3B82F6), Icons.Default.DirectionsCar, // Bright Blue
        setOf("travel", "transport", "transportation", "cab", "cabs", "taxi", "auto", "uber", "ola", "rapido",
            "metro", "bus", "train", "commute", "ride", "rides", "ksrtc", "irctc", "flight")
    ),
    ENTERTAINMENT(
        Color(0xFFEC4899), Icons.Default.Movie, // Pink
        setOf("entertainment", "movie", "movies", "games", "gaming", "netflix", "subscription",
            "subscriptions", "ott", "youtube", "hotstar", "prime", "pvr", "inox", "bookmyshow")
    ),
    BILLS(
        Color(0xFF06B6D4), Icons.Default.ReceiptLong, // Cyan
        setOf("bills", "bill", "utilities", "utility", "electricity", "internet", "wifi", "broadband",
            "emi", "insurance", "loan", "kseb", "bescom", "water", "dth")
    ),
    TRANSFER(
        Color(0xFF64748B), Icons.AutoMirrored.Filled.CompareArrows, // Slate
        setOf("transfer", "transfers", "self", "sweep")
    ),
    BANK(
        Color(0xFF64748B), Icons.Default.AccountBalance, // Slate
        setOf("bank", "charges", "charge", "fee", "fees", "interest", "tax", "taxes", "gst", "penalty")
    ),
    HEALTH(
        Color(0xFFEF4444), Icons.Default.LocalHospital, // Crisp Red
        setOf("health", "healthcare", "medical", "medicine", "medicines", "pharmacy", "hospital", "doctor", "clinic", "dental", "apollo")
    ),
    HOME(
        Color(0xFF6366F1), Icons.Default.Home, // Indigo
        setOf("rent", "home", "housing", "household", "maintenance", "furniture", "hostel", "pg")
    ),
    EDUCATION(
        Color(0xFF84CC16), Icons.Default.School, // Lime
        setOf("education", "school", "tuition", "course", "courses", "books", "study", "college", "exam")
    ),
    INVESTMENTS(
        Color(0xFF14B8A6), Icons.AutoMirrored.Filled.TrendingUp, // Teal
        setOf("investment", "investments", "mutual", "fund", "funds", "sip", "stocks", "stock", "zerodha", "groww", "upstox", "indmoney")
    ),
    OTHER(null, Icons.Default.Category, emptySet())
}

private val wordSplitter = Regex("[^a-z0-9]+")
private val kindCache = ConcurrentHashMap<String, CategoryKind>()

private fun kindOf(category: String): CategoryKind = kindCache.getOrPut(category) {
    val words = category.lowercase().split(wordSplitter).filter { it.isNotEmpty() }.toSet()
    CategoryKind.values().firstOrNull { kind -> kind.keywords.any { it in words } } ?: CategoryKind.OTHER
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