package com.expensetracker.offline.engine.categorizer

import com.expensetracker.offline.data.local.dao.CategoryRuleDao
import com.expensetracker.offline.engine.ml.SmsPatternLearner
import java.util.Locale

object CategoryClassifier {

    val PREDEFINED_CATEGORIES = listOf(
        "Food & Dining",
        "Groceries",
        "Fuel & Fastag",
        "Travel",
        "Recharge & Telecom",
        "Bills & Utilities",
        "Rent & Housing",
        "Household & Services",
        "Shopping",
        "EMI & Loans",
        "Subscriptions",
        "Entertainment",
        "Health",
        "Personal Care",
        "Education",
        "Investments",
        "Gifts & Festivals",
        "Transfer",
        "Uncategorized"
    )

    /**
     * Generic placeholder payee names generated when a bank SMS does not include a merchant name.
     * These must NEVER be stored in `category_rules`, cached by payee name, or used for fuzzy/Bayesian
     * category inheritance across unrelated transactions.
     */
    val GENERIC_FALLBACK_PAYEES: Set<String> = setOf(
        "bank transfer",
        "upi payment",
        "bank credit",
        "upi received",
        "card spend",
        "atm withdrawal",
        "unknown",
        "silent deduction",
        "self transfer",
        "credit card bill",
        "fastag toll",
        "payment",
        "transfer",
        "merchant",
        "upi"
    )

    fun isGenericPayee(payee: String?): Boolean {
        if (payee.isNullOrBlank()) return true
        val norm = payee.trim().lowercase(Locale.ROOT)
        if (norm.length < 2) return true
        return norm in GENERIC_FALLBACK_PAYEES || norm.startsWith("balance sync") || norm.startsWith("transfer (")
    }

    // Payee-level Regex map (checked against extracted merchant/payee name).
    private val PAYEE_CATEGORY_REGEX_MAP: Map<String, Regex> = linkedMapOf(
        "Transfer" to createRegex(
            "self transfer", "credit card bill", "cc bill", "internal transfer",
            "auto sweep", "own a/c", "sweep in", "sweep out", "upi lite load", "wallet load"
        ),
        "Groceries" to createRegex(
            "blinkit", "zepto", "instamart", "swiggy instamart", "bigbasket", "bbnow", "bb daily",
            "dmart", "d-mart", "avenue supermarts", "grofers", "spencer", "spencers", "supermarket",
            "grocery", "groceries", "vegetables?", "veggies", "sabzi", "mandi", "fruits?",
            "nature basket", "natures basket", "reliance fresh", "reliance smart", "jiomart",
            "more supermarket", "more retail", "lulu hypermarket", "lulu daily", "hypermarket",
            "ratnadeep", "pazhamudir", "nilgiris", "margin free", "supplyco", "triveni", "safal",
            "mother dairy", "amul", "nandini", "milma", "aavin", "verka", "country delight",
            "akshayakalpa", "licious", "freshtohome", "tender cuts", "zappfresh", "star bazaar",
            "spar hypermarket", "kirana", "provisions?", "minimart", "general store",
            "departmental store", "dairy", "milk", "butcher", "fish market", "poultry", "atta chakki",
            "ma mart", "super mart", "fresh mart", "supermarket"
        ),
        "Food & Dining" to createRegex(
            "starbucks", "swiggy", "zomato", "dineout", "eazy diner", "magicpin", "eatsure",
            "rebel foods", "faasos", "behrouz", "oven story", "lunchbox", "mcdonalds?", "kfc",
            "burger", "burger king", "cafe", "restaurant", "pizza", "pizza hut", "subway",
            "dominos?", "tea", "coffee", "bistro", "bakery", "bakers", "hotel", "shawarma",
            "biryani", "broast", "shawaya", "mandhi", "kuzhimanthi", "alfaham", "canteen", "mess",
            "coolbar", "bakes", "juice", "chai", "chaayos", "chai point", "chai sutta",
            "third wave", "blue tokai", "ccd", "cafe coffee day", "diner", "tiffin", "eatery",
            "kitchen", "grill", "haldirams?", "bikanervala", "a2b", "adyar ananda",
            "saravana bhavan", "rameshwaram cafe", "udupi", "udipi", "darshini", "aryas",
            "indian coffee house", "paragon", "rahmath", "thalappakatti", "paradise biryani",
            "meghana foods", "empire restaurant", "bawarchi", "pista house", "al-baik", "al baik",
            "barbeque nation", "wow momo", "taco bell", "baskin robbins", "naturals ice", "ibaco",
            "polar bear", "corner house", "keventers", "belgian waffle", "waffles?", "theobroma",
            "karachi bakery", "monginis", "mio amore", "just bake", "smoor", "sri krishna sweets",
            "dhaba", "bhojanalaya", "thali", "food court", "momos", "rolls", "kathi", "frankie",
            "chaat", "pani puri", "samosa", "vada pav", "misal", "pav bhaji", "dosa", "idli",
            "parotta", "porotta", "puttu", "thattukada", "tapri", "lassi", "shake", "smoothie",
            "sweets", "mithai", "namkeen", "farsan", "snacks", "restobar", "pub", "brewery",
            "kebab", "tandoori", "chicken", "mutton", "ice cream", "icecream", "kulfi", "falooda", "meals", "bhavan"
        ),
        "Fuel & Fastag" to createRegex(
            "hpcl", "hindustan petroleum", "hp pay", "bpcl", "bharat petroleum", "smartdrive",
            "iocl", "indian oil", "xtrapower", "shell india", "nayara", "essar oil", "jio-bp",
            "jiobp", "reliance petroleum", "mrpl", "igl", "indraprastha gas", "mgl", "mahanagar gas",
            "adani gas", "gujarat gas", "torrent gas", "fuel", "petrol", "diesel", "cng",
            "petrol pump", "petrol bunk", "filling station", "service station", "autogas",
            "fastag", "netc fastag", "ihmcl", "nhai", "toll", "toll plaza", "tollway",
            "park\\+", "parkplus", "parking", "ather grid", "statiq", "tata power ez",
            "zeon charging", "kazam", "chargezone", "ev charging"
        ),
        "Travel" to createRegex(
            "uber", "ola", "ola cabs", "rapido", "namma yatri", "yatri sathi", "mana yatri",
            "blusmart", "meru", "indrive", "savaari", "quick ride", "yulu", "bounce", "zoomcar",
            "royal brothers", "dmrc", "delhi metro", "bmrcl", "namma metro", "cmrl",
            "chennai metro", "kmrl", "kochi metro", "mmrda", "mumbai metro", "hmrl",
            "hyderabad metro", "mahametro", "pune metro", "kolkata metro", "water metro",
            "irctc", "railway", "indian railways", "railone", "confirmtkt", "railyatri",
            "redbus", "abhibus", "zingbus", "makemytrip", "goibibo", "cleartrip", "ixigo",
            "easemytrip", "yatra", "agoda", "booking\\.com", "airbnb", "oyo", "treebo", "fabhotels",
            "zostel", "taj hotels", "lemon tree", "indigo", "interglobe", "air india",
            "vistara", "akasa", "spicejet", "ksrtc", "msrtc", "apsrtc", "tsrtc", "tnstc",
            "bmtc", "best undertaking", "upsrtc", "gsrtc", "rsrtc", "hrtc", "chalo",
            "tummoc", "rickshaw", "autorickshaw", "cabs", "taxis", "ferry", "airport"
        ),
        "Recharge & Telecom" to createRegex(
            "jio", "myjio", "reliance jio", "jio prepaid", "jio postpaid", "jiofiber", "jio fiber",
            "jioairfiber", "airtel", "bharti airtel", "airtel thanks", "airtel xstream",
            "airtel black", "vi recharge", "vodafone", "vodafone idea", "bsnl", "mtnl",
            "act fibernet", "atria convergence", "hathway", "excitel", "tikona", "you broadband",
            "gtpl", "kerala vision", "asianet broadband", "railwire", "alliance broadband",
            "tata play", "tata sky", "dish tv", "d2h", "sun direct", "airtel digital tv",
            "recharge", "dth", "postpaid", "prepaid", "data pack", "topup", "talktime", "telecom"
        ),
        "Bills & Utilities" to createRegex(
            "electricity", "kseb", "ksebl", "bescom", "tneb", "tangedco", "msedcl", "mahavitaran",
            "tata power", "adani electricity", "bses", "brpl", "bypl", "tpddl", "cesc", "uppcl",
            "pspcl", "uhbvn", "dhbvn", "wbsedcl", "dgvcl", "mgvcl", "pgvcl", "ugvcl",
            "torrent power", "tsspdcl", "tsnpdcl", "apepdcl", "apspdcl", "hescom", "mescom",
            "cescom", "gescom", "jvvnl", "avvnl", "jdvvnl", "nbpdcl", "sbpdcl", "apdcl",
            "indane", "bharatgas", "bharat gas", "hp gas", "lpg", "gas cylinder", "cooking gas",
            "piped gas", "gail", "bwssb", "djb", "delhi jal", "kwa", "kerala water",
            "hmwssb", "mcgm", "cmwssb", "metrowater", "broadband", "wifi", "billdesk",
            "bbps", "bharat billpay", "ebill", "power bill", "water bill", "utility bill",
            "property tax", "municipal", "panchayat", "ksmart", "meeseva", "eseva", "echallan"
        ),
        "Rent & Housing" to createRegex(
            "house rent", "flat rent", "room rent", "pg rent", "hostel rent", "shop rent",
            "office rent", "nobroker", "nobroker pay", "nobroker hood", "mygate",
            "adda\\.io", "apnacomplex", "society maintenance", "apartment maintenance",
            "rwa charges", "maintenance bill", "housing society", "stanza living", "zolo",
            "colive", "your-space", "nestaway", "landlord", "security deposit", "brokerage"
        ),
        "Household & Services" to createRegex(
            "urban company", "urbanclap", "housejoy", "onsitego", "oneassist", "servify",
            "tumbledry", "uclean", "pressto", "fabrico", "laundry", "dry clean", "dryclean",
            "dhobi", "ironing", "livpure", "drinkprime", "aquaguard", "eureka forbes", "kent ro",
            "bisleri", "water can", "water jar", "maid", "domestic help", "house help",
            "cook salary", "driver salary", "gardener", "watchman", "car wash", "plumber",
            "plumbing", "electrician", "carpenter", "painter", "ac repair", "ac service",
            "appliance repair", "pest control", "deep cleaning", "packers", "porter"
        ),
        "EMI & Loans" to createRegex(
            "bajaj finance", "bajaj finserv", "hdb financial", "tata capital",
            "aditya birla finance", "l&t finance", "ltfs", "cholamandalam", "shriram finance",
            "mahindra finance", "sundaram finance", "muthoot", "manappuram", "iifl finance",
            "piramal finance", "poonawalla fincorp", "home credit", "kreditbee", "moneyview",
            "navi finserv", "kissht", "ring pay", "cashe", "fibe", "earlysalary", "mpokket",
            "lazypay", "simpl", "slice pay", "zestmoney", "axio", "capital float", "uni card",
            "cred", "cheq", "loan emi", "home loan", "car loan", "vehicle loan", "personal loan",
            "education loan", "gold loan", "nach debit", "ecs debit"
        ),
        "Subscriptions" to createRegex(
            "netflix", "hotstar", "disney\\+ hotstar", "spotify", "prime video", "amazon prime",
            "youtube", "youtube premium", "youtube music", "sony liv", "sonyliv", "jiocinema",
            "zee5", "sun nxt", "aha video", "hoichoi", "manoramamax", "stage app", "lionsgate",
            "discovery\\+", "mubi", "crunchyroll", "apple music", "jiosaavn", "gaana", "wynk",
            "audible", "kindle", "storytel", "kukufm", "pocket fm", "headspace", "calm",
            "apple services", "icloud", "google play", "google one", "google photos",
            "microsoft 365", "onedrive", "dropbox", "chatgpt", "openai", "claude", "anthropic",
            "perplexity", "midjourney", "github", "cursor", "canva", "adobe", "figma", "notion",
            "grammarly", "linkedin", "truecaller", "swiggy one", "zomato gold", "times prime",
            "et prime", "moneycontrol pro"
        ),
        "Entertainment" to createRegex(
            "pvr", "inox", "pvr inox", "cinepolis", "miraj cinemas", "carnival cinemas",
            "ags cinemas", "sathyam cinemas", "spi cinemas", "bookmyshow", "paytm insider",
            "district", "steam", "playstation", "psn", "xbox", "epic games", "nintendo",
            "riot games", "krafton", "bgmi", "wonderla", "imagicaa", "esselworld", "ramoji",
            "snow world", "veega land", "smaash", "timezone", "funcity", "amoeba", "bowling",
            "go karting", "laser tag", "playo", "hudle", "turf", "badminton court", "box cricket",
            "futsal", "gaming", "cinema", "movies", "theatre", "multiplex", "concert"
        ),
        "Personal Care" to createRegex(
            "nykaa", "purplle", "tira", "sephora", "mac cosmetics", "sugar cosmetics",
            "mamaearth", "minimalist", "plum goodness", "dot and key", "wow skin", "mcaffeine",
            "forest essentials", "kama ayurveda", "body shop", "bath and body", "lakme salon",
            "naturals salon", "green trends", "enrich salon", "toni and guy", "jawed habib",
            "ylg salon", "geetanjali salon", "truefitt", "bombay shaving", "beardo", "ustraa",
            "salon", "spa", "parlour", "beauty parlour", "barber", "haircut", "grooming",
            "facial", "manicure", "pedicure", "cosmetics", "skincare"
        ),
        "Education" to createRegex(
            "physicswallah", "pw live", "allen career", "aakash institute", "resonance",
            "fiitjee", "motion education", "narayana group", "sri chaitanya", "brilliant pala",
            "xylem learning", "eduport", "entri app", "adda247", "testbook", "oliveboard",
            "byjus", "byju's", "unacademy", "vedantu", "toppr", "extramarks", "doubtnut",
            "upgrad", "scaler", "great learning", "simplilearn", "masai school", "nxtwave",
            "coding ninjas", "coursera", "udemy", "edx", "pluralsight", "skillshare", "datacamp",
            "leetcode", "duolingo", "sapna book", "dc books", "mathrubhumi books", "higginbothams",
            "crossword", "scholastic", "nta exam", "upsc", "ssc fee", "ibps", "gate exam",
            "cat exam", "icai", "school fee", "college fee", "university fee", "tuition",
            "coaching", "academy", "institute", "semester fee", "exam fee", "admission fee",
            "bookstore", "book stall", "stationery", "xerox", "photocopy"
        ),
        "Shopping" to createRegex(
            "amazon", "amzn", "flipkart", "myntra", "meesho", "ajio", "zara", "h&m", "uniqlo",
            "marks and spencer", "clothing", "apparel", "garments", "textiles",
            "sarees", "boutique", "electronics", "appliances", "decathlon", "wildcraft",
            "vip bags", "american tourister", "safari bags", "croma", "reliance digital",
            "vijay sales", "poorvika", "sangeetha mobiles", "sathya agencies", "pai international",
            "bajaj electronics", "aditya vision", "myg", "oxygen digital", "nandilath",
            "pittappillil", "apple store", "imagine store", "samsung smartcafe", "mi home",
            "lifestyle", "shoppers stop", "tatacliq", "tata cliq", "snapdeal", "shopsy",
            "bewakoof", "souled store", "snitch", "rare rabbit", "reliance trends",
            "zudio", "westside", "pantaloons", "max fashion", "vishal mega mart", "v-mart",
            "style union", "fabindia", "biba", "manyavar", "raymond", "peter england",
            "louis philippe", "van heusen", "allen solly", "levi's", "levis", "pepe jeans",
            "us polo", "jockey", "nike", "adidas", "puma", "reebok", "skechers", "asics",
            "crocs", "bata", "metro shoes", "mochi", "khadim", "footwear", "ikea", "pepperfry",
            "urban ladder", "home centre", "wakefit", "sleepwell", "nilkamal", "furniture",
            "tanishq", "caratlane", "kalyan jewellers", "malabar gold", "joyalukkas",
            "bhima jewellers", "pc jeweller", "senco gold", "grt jewellers", "bluestone", "giva",
            "jewellery", "jewelry", "titan", "fastrack", "helios", "casio", "lenskart",
            "titan eye", "specsmakers", "firstcry", "hamleys", "miniso", "chumbak", "archies",
            "saravana stores", "pothys", "chennai silks", "rmkv", "nalli", "kalyan silks",
            "jayalakshmi", "seemas", "pulimoottil", "fancy store"
        ),
        "Health" to createRegex(
            "apollo", "apollo pharmacy", "apollo hospitals", "pharmeasy", "1mg", "tata 1mg",
            "netmeds", "wellness forever", "frank ross", "sasta sundar", "zeelab", "neethi",
            "karunya", "jan aushadhi", "pharmacy", "chemist", "druggist", "hospital", "clinic",
            "polyclinic", "dispensary", "dentist", "dental", "clove dental", "medplus",
            "diagnostic", "diagnostics", "pathology", "lal pathlabs", "dr lal", "metropolis",
            "thyrocare", "redcliffe labs", "srl diagnostics", "agilus", "vijaya diagnostic",
            "aarthi scans", "ddrc", "aster", "manipal hospital", "fortis",
            "max healthcare", "narayana health", "kims hospital", "amrita hospital", "rajagiri",
            "lakeshore", "baby memorial", "mims", "cmc vellore", "aiims", "medanta", "kokilaben",
            "lilavati", "hinduja", "yashoda", "care hospitals", "kauvery", "miot",
            "sankara nethralaya", "aravind eye", "dr agarwal", "medibuddy", "practo", "mfine",
            "medicals?", "medical store", "medicine", "medicines", "ayurveda", "ayurvedic",
            "kottakkal", "homeopathy", "physiotherapy", "blood test", "mri", "ct scan",
            "star health", "niva bupa", "care health", "ditto insurance", "policybazaar",
            "gym", "fitness", "cult\\.fit", "cultfit", "curefit", "golds gym", "anytime fitness",
            "healthkart", "muscleblaze", "optimum nutrition", "myprotein", "avvatar",
            "creatine", "protein", "whey", "workout", "yoga"
        ),
        "Investments" to createRegex(
            "zerodha", "groww", "upstox", "rksv", "angel one", "angel broking", "dhan",
            "paytm money", "indmoney", "kuvera", "et money", "smallcase", "scripbox", "dezerv",
            "motilal oswal", "icici direct", "hdfc securities", "hdfc sky", "kotak securities",
            "kotak neo", "axis direct", "sbi securities", "sharekhan", "5paisa", "geojit",
            "mstock", "fyers", "shoonya", "alice blue", "cams", "kfintech", "mf utility", "mfu",
            "bse star", "nse nmf", "iccl", "sbi mf", "hdfc mf", "icici prudential mf",
            "nippon india", "kotak mf", "axis mf", "uti mf", "parag parikh", "ppfas", "quant mf",
            "mirae asset", "dsp mf", "jar app", "gullak", "safegold", "augmont", "mmtc pamp",
            "sovereign gold", "sgb", "digital gold", "life insurance corp", "sbi life",
            "hdfc life", "icici prudential", "max life", "tata aia", "protean", "nps", "epfo",
            "ppf", "sukanya samriddhi", "wint wealth", "goldenpi", "grip invest", "stable money",
            "mutual fund", "cleartax", "equities", "demat", "broking",
            "fixed deposit", "recurring deposit", "chit fund", "ksfe"
        ),
        "Gifts & Festivals" to createRegex(
            "fnp", "ferns n petals", "igp", "indian gifts portal", "floweraura", "winni",
            "tirupati", "ttd", "guruvayur", "sabarimala", "travancore devaswom",
            "cochin devaswom", "malabar devaswom", "shirdi", "siddhivinayak", "vaishno devi",
            "iskcon", "isha foundation", "art of living", "ramakrishna mission", "ketto",
            "milaap", "giveindia", "impactguru", "cry india", "goonj", "akshaya patra",
            "helpage india", "pm cares", "cmdrf", "shagun", "dakshina", "prasadam", "vazhipadu",
            "archana", "pooja", "puja", "temple", "mandir", "mosque", "masjid", "zakat",
            "sadaqah", "church offering", "tithe", "gurudwara", "langar", "charity", "donation",
            "bouquet", "florist", "gift shop"
        )
    )

    // Strict raw SMS content regex map: only unambiguous brand/merchant names so standard bank SMS
    // boilerplate (e.g. "auto-debit", "auto sweep", "toll free", "call", "sms") never triggers a false category.
    private val RAW_CONTENT_CATEGORY_REGEX_MAP: Map<String, Regex> = linkedMapOf(
        "Transfer" to createRegex(
            "self transfer", "credit card bill", "cc bill", "internal transfer",
            "auto sweep", "own a/c", "sweep in", "sweep out", "upi lite load"
        ),
        "Groceries" to createRegex(
            "blinkit", "zepto", "instamart", "bigbasket", "bbnow", "dmart", "d-mart",
            "avenue supermarts", "reliance fresh", "reliance smart", "jiomart", "lulu hypermarket",
            "supplyco", "milma", "nandini", "country delight", "licious", "freshtohome"
        ),
        "Food & Dining" to createRegex(
            "starbucks", "swiggy", "zomato", "dineout", "eatsure", "faasos", "behrouz",
            "mcdonalds?", "kfc", "burger king", "pizza hut", "dominos?", "chaayos",
            "third wave", "blue tokai", "cafe coffee day", "haldirams?", "bikanervala",
            "saravana bhavan", "rameshwaram cafe", "barbeque nation", "theobroma"
        ),
        "Fuel & Fastag" to createRegex(
            "hpcl", "hindustan petroleum", "bpcl", "bharat petroleum", "iocl", "indian oil",
            "shell india", "nayara", "netc fastag", "fastag", "ihmcl", "nhai", "parkplus"
        ),
        "Travel" to createRegex(
            "uber", "ola cabs", "rapido", "namma yatri", "blusmart", "irctc", "indian railways",
            "confirmtkt", "redbus", "abhibus", "makemytrip", "goibibo", "cleartrip", "ixigo",
            "easemytrip", "airbnb", "indigo", "interglobe", "air india", "vistara", "akasa",
            "spicejet", "ksrtc", "msrtc", "apsrtc", "tsrtc", "tnstc", "bmtc", "kochi metro",
            "delhi metro", "namma metro", "chennai metro", "mumbai metro", "hyderabad metro"
        ),
        "Recharge & Telecom" to createRegex(
            "myjio", "reliance jio", "jiofiber", "bharti airtel", "airtel thanks",
            "vodafone idea", "bsnl", "act fibernet", "kerala vision", "asianet broadband",
            "tata play", "tata sky", "sun direct"
        ),
        "Bills & Utilities" to createRegex(
            "kseb", "ksebl", "bescom", "tneb", "tangedco", "msedcl", "mahavitaran",
            "adani electricity", "tpddl", "cesc", "uppcl", "pspcl", "wbsedcl", "tsspdcl",
            "indane", "bharatgas", "hp gas", "bwssb", "kerala water", "bharat billpay"
        ),
        "Rent & Housing" to createRegex(
            "nobroker", "mygate", "apnacomplex", "stanza living"
        ),
        "Household & Services" to createRegex(
            "urban company", "urbanclap", "tumbledry", "uclean", "livpure", "drinkprime"
        ),
        "EMI & Loans" to createRegex(
            "bajaj finance", "bajaj finserv", "hdb financial", "tata capital", "cholamandalam",
            "shriram finance", "muthoot", "manappuram", "kreditbee", "moneyview", "nach debit", "ecs debit"
        ),
        "Subscriptions" to createRegex(
            "netflix", "hotstar", "spotify", "amazon prime", "youtube premium", "sonyliv",
            "jiocinema", "zee5", "manoramamax", "apple music", "google play", "google one",
            "microsoft 365", "chatgpt", "openai", "claude", "anthropic", "canva", "adobe"
        ),
        "Entertainment" to createRegex(
            "pvr inox", "cinepolis", "bookmyshow", "steam", "playstation", "wonderla"
        ),
        "Personal Care" to createRegex(
            "nykaa", "purplle", "sephora", "mamaearth", "minimalist", "lakme salon", "naturals salon"
        ),
        "Education" to createRegex(
            "physicswallah", "allen career", "aakash institute", "brilliant pala", "xylem learning",
            "eduport", "entri app", "adda247", "testbook", "unacademy", "coursera", "udemy", "dc books"
        ),
        "Shopping" to createRegex(
            "amazon", "flipkart", "myntra", "meesho", "ajio", "zara", "uniqlo", "decathlon",
            "croma", "reliance digital", "vijay sales", "poorvika", "myg", "oxygen digital",
            "nandilath", "pittappillil", "shoppers stop", "tata cliq", "zudio", "westside",
            "pantaloons", "max fashion", "ikea", "pepperfry", "wakefit", "tanishq", "caratlane",
            "kalyan jewellers", "malabar gold", "joyalukkas", "bhima jewellers", "lenskart",
            "firstcry", "kalyan silks", "jayalakshmi", "seemas", "pulimoottil"
        ),
        "Health" to createRegex(
            "apollo pharmacy", "apollo hospitals", "pharmeasy", "tata 1mg", "netmeds", "medplus",
            "lal pathlabs", "thyrocare", "ddrc", "manipal hospital", "fortis", "amrita hospital",
            "rajagiri", "lakeshore", "baby memorial", "cultfit", "healthkart", "muscleblaze"
        ),
        "Investments" to createRegex(
            "zerodha", "groww", "upstox", "angel one", "paytm money", "indmoney", "kuvera",
            "smallcase", "geojit", "cams", "kfintech", "sbi mf", "hdfc mf", "parag parikh",
            "jar app", "gullak", "safegold", "ksfe"
        ),
        "Gifts & Festivals" to createRegex(
            "ferns n petals", "floweraura", "guruvayur", "sabarimala", "travancore devaswom",
            "cochin devaswom", "malabar devaswom", "iskcon", "isha foundation", "ketto", "milaap"
        )
    )

    private fun createRegex(vararg keywords: String): Regex {
        val pattern = keywords.joinToString(separator = "|")
        return Regex("(?i)\\b($pattern)\\b")
    }

    fun classifySync(payee: String, rawContent: String): String {
        val cleanPayee = payee.trim()
        val generic = isGenericPayee(cleanPayee)

        // 0. Check VPA memory first
        if (!generic) {
            SmsPatternLearner.lookupVpa(cleanPayee)?.category?.takeIf { !it.equals("Uncategorized", ignoreCase = true) }?.let {
                return it
            }
        }
        SmsPatternLearner.lookupVpa(rawContent)?.category?.takeIf { !it.equals("Uncategorized", ignoreCase = true) }?.let {
            return it
        }

        if (!generic) {
            // Fold spelling variants first so "Biriyani" / "Cofee" / "Super Market" hit the same keyword rules.
            val canonical = PayeeHeuristics.canonicalize(cleanPayee)
            for ((category, regex) in PAYEE_CATEGORY_REGEX_MAP) {
                if (regex.containsMatchIn(canonical)) {
                    return category
                }
            }
        }

        if (rawContent.isNotBlank() && rawContent != cleanPayee) {
            for ((category, regex) in RAW_CONTENT_CATEGORY_REGEX_MAP) {
                if (regex.containsMatchIn(rawContent)) {
                    return category
                }
            }
        }

        // Last: generic shop-type words and one-letter typos ("... Foods", "... Medicals", "Restorent").
        if (!generic) PayeeHeuristics.categoryHint(cleanPayee)?.let { return it }

        return "Uncategorized"
    }

    private val categoryCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun clearCache() {
        categoryCache.clear()
        NaiveBayesCategorizer.invalidate()
    }

    suspend fun classify(
        payee: String,
        rawContent: String,
        ruleDao: CategoryRuleDao
    ): String {
        val cleanPayee = payee.trim().lowercase(Locale.ROOT)
        val generic = isGenericPayee(cleanPayee)

        // 0. Check VPA memory
        if (!generic) {
            SmsPatternLearner.lookupVpa(cleanPayee)?.category?.takeIf { !it.equals("Uncategorized", ignoreCase = true) }?.let {
                return it
            }
        }
        SmsPatternLearner.lookupVpa(rawContent)?.category?.takeIf { !it.equals("Uncategorized", ignoreCase = true) }?.let {
            return it
        }

        if (cleanPayee.isNotBlank() && !generic) {
            categoryCache[cleanPayee]?.let { return it }

            // 1. Exact or substring learned rule (never query for generic fallback payees)
            val learned = ruleDao.findMatchingCategory(cleanPayee)
            if (!learned.isNullOrBlank() && !learned.equals("Uncategorized", ignoreCase = true)) {
                categoryCache[cleanPayee] = learned
                return learned
            }

            // 2. Known-merchant regex on the payee (spelling variants folded first)
            val canonical = PayeeHeuristics.canonicalize(cleanPayee)
            for ((category, regex) in PAYEE_CATEGORY_REGEX_MAP) {
                if (regex.containsMatchIn(canonical)) {
                    categoryCache[cleanPayee] = category
                    return category
                }
            }

            // 3. Subword 3-gram + word token + bigram Naive Bayes generalization from taught rules
            NaiveBayesCategorizer.predict(cleanPayee, ruleDao)?.let {
                categoryCache[cleanPayee] = it.category
                return it.category
            }

            // 3b. Generic shop-type words and typos. Below anything the user taught, above raw SMS text.
            PayeeHeuristics.categoryHint(cleanPayee)?.let {
                categoryCache[cleanPayee] = it
                return it
            }
        }

        // 4. Last resort: scan the raw SMS/notification text for unambiguous brand names only
        if (rawContent.isNotBlank()) {
            for ((category, regex) in RAW_CONTENT_CATEGORY_REGEX_MAP) {
                if (regex.containsMatchIn(rawContent)) return category
            }
        }
        return "Uncategorized"
    }
}