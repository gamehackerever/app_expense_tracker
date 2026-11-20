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
        "Unspecified",
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
        return norm.length < 2 || norm in GENERIC_FALLBACK_PAYEES || norm.startsWith("balance sync") || norm.startsWith("transfer (")
    }

    // Payee-level Regex map (checked against extracted merchant/payee name).
    private val PAYEE_CATEGORY_REGEX_MAP: Map<String, Regex> = linkedMapOf(
        "Transfer" to createRegex(
            "self transfer", "credit card bill", "cc bill", "internal transfer",
            "auto sweep", "own a/c", "sweep in", "sweep out", "upi lite load", "wallet load"
        ),
        "Food & Dining" to createRegex(
            "puffs?", "egg\\s+puff", "veg\\s+puff", "chicken\\s+puff", "paneer\\s+puff",
            "egg\\s+roll", "egg\\s+curry", "egg\\s+bhurji", "egg\\s+fried\\s+rice", "egg\\s+rice", "egg\\s+toast", "egg\\s+noodles",
            "omelet", "omelette", "omlet", "boiled\\s+egg",
            "breakfast", "lunch", "dinner", "supper", "brunch", "meals?", "tiffin",
            "sandwiches?", "sandwich", "toast", "noodles", "chowmein", "fried\\s+rice", "pasta",
            "kachori", "jalebi", "pakoda", "pakoras?", "bhajji", "bajjis?", "vada", "medu\\s+vada",
            "dosa", "masala\\s+dosa", "idli", "vada\\s+pav", "pav\\s+bhaji", "misal\\s+pav", "poori", "puri",
            "chole\\s+bhature", "bhatura", "parathas?", "parotta", "porotta", "aloo\\s+paratha", "rotis?", "naan", "kulcha", "chapati",
            "pastry", "pastries", "brownies?", "cakes?", "donuts?", "doughnuts?", "desserts?", "waffles?",
            "cappuccino", "latte", "espresso", "soda", "cold\\s+drinks?",
            "gulab\\s+jamun", "rasgulla", "kaju\\s+katli", "halwa", "laddus?", "ladoos?", "barfi", "pedas?",
            "starbucks", "swiggy", "zomato", "dineout", "eazy diner", "magicpin", "eatsure",
            "rebel foods", "faasos", "behrouz", "oven story", "lunchbox", "mcdonalds?", "kfc",
            "burger", "burger king", "cafe", "restaurant", "pizza", "pizza hut", "subway",
            "dominos?", "tea", "coffee", "bistro", "bakery", "bakers", "hotel", "shawarma",
            "biryani", "broast", "shawaya", "mandhi", "kuzhimanthi", "alfaham", "canteen", "mess",
            "coolbar", "bakes", "juice", "chai", "chaayos", "chai point", "chai sutta",
            "third wave", "blue tokai", "ccd", "cafe coffee day", "diner", "eatery",
            "kitchen", "grill", "haldirams?", "bikanervala", "a2b", "adyar ananda",
            "saravana bhavan", "rameshwaram cafe", "udupi", "udipi", "darshini", "aryas",
            "indian coffee house", "paragon", "rahmath", "thalappakatti", "paradise biryani",
            "meghana foods", "empire restaurant", "bawarchi", "pista house", "al-baik", "al baik",
            "barbeque nation", "wow momo", "taco bell", "baskin robbins", "naturals ice", "ibaco",
            "polar bear", "corner house", "keventers", "belgian waffle", "theobroma",
            "karachi bakery", "monginis", "mio amore", "just bake", "smoor", "sri krishna sweets",
            "dhaba", "bhojanalaya", "thali", "food court", "momos", "rolls", "kathi", "frankie",
            "chaat", "pani puri", "samosas?", "puttu", "thattukada", "tapri", "lassi", "shakes?", "milkshakes?", "smoothies?",
            "sweets", "mithai", "namkeen", "farsan", "snacks", "restobar", "pub", "brewery",
            "kebab", "tandoori", "chicken", "mutton", "ice\\s*cream", "icecream", "kulfi", "falooda", "meals", "bhavan",
            "food", "foods", "eats", "refreshments?", "bites"
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
            "departmental store", "dairy", "milk", "curd", "dahi", "paneer", "butter", "cheese", "ghee",
            "rice", "wheat", "atta", "flour", "dals?", "pulses", "oil", "cooking\\s+oil", "spices", "masala", "salt", "sugar",
            "eggs?", "butcher", "fish market", "poultry", "atta chakki", "bread", "biscuits?", "maggi", "cereals?", "oats",
            "ma mart", "super mart", "fresh mart", "supermarket"
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
            "book\\s*my\\s*show", "bookmyshow", "bigtree\\s*(?:entertainment)?", "bigtree", "bms",
            "pvr", "inox", "pvr inox", "cinepolis", "miraj cinemas", "carnival cinemas",
            "ags cinemas", "sathyam cinemas", "spi cinemas", "paytm insider", "ticketnew",
            "insider\\.in", "district", "steam", "playstation", "psn", "xbox", "epic games", "nintendo",
            "riot games", "krafton", "bgmi", "wonderla", "imagicaa", "esselworld", "ramoji",
            "snow world", "veega land", "smaash", "timezone", "funcity", "amoeba", "bowling",
            "go karting", "laser tag", "playo", "hudle", "turf", "badminton court", "box cricket",
            "futsal", "gaming", "cinema", "movies?", "theatre", "multiplex", "concert",
            "movie\\s*tickets?", "cinema\\s*tickets?", "theatre\\s*tickets?", "concert\\s*tickets?", "show\\s*tickets?"
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
            "pens?", "ball\\s*pens?", "gel\\s*pens?", "fountain\\s*pens?", "ink\\s*pens?", "pilot\\s*pens?", "sketch\\s*pens?",
            "pencils?", "lead\\s*pencils?", "colour\\s*pencils?", "color\\s*pencils?",
            "stationery", "stationary", "staionery", "stationers?", "stationery\\s*mart", "stationery\\s*shop", "stationery\\s*store",
            "notebooks?", "notepads?", "registers?", "erasers?", "sharpeners?", "rulers?", "geometry\\s*box", "compass",
            "highlighters?", "markers?", "whiteboard\\s*markers?", "staplers?", "staple\\s*pins?", "fevicol", "glue\\s*stick", "paper\\s*clips?",
            "chart\\s*papers?", "a4\\s*sheets?", "a4\\s*papers?", "printer\\s*papers?", "drawing\\s*books?", "craft\\s*papers?",
            "classmate", "camlin", "kokuyo", "doms", "apsara", "nataraj", "reynolds", "flair\\s*(?:pen)?", "luxor", "uniball", "staedtler", "faber\\s*castell", "parker\\s*(?:pen)?",
            "xerox", "photostat", "printouts?", "spiral\\s*binding", "lamination", "photocopy",
            "bookstore", "book\\s*store", "book\\s*depot", "book\\s*stall", "book\\s*shop", "crossword\\s*bookstores?", "sapna\\s*book", "dc\\s*books", "mathrubhumi\\s*books", "higginbothams",
            "crossword", "scholastic",
            "physicswallah", "pw\\b", "pw live", "allen career", "aakash institute", "resonance",
            "fiitjee", "motion education", "narayana group", "sri chaitanya", "brilliant pala",
            "xylem learning", "eduport", "entri app", "adda247", "testbook", "oliveboard",
            "byjus", "byju's", "unacademy", "vedantu", "toppr", "extramarks", "doubtnut",
            "upgrad", "scaler", "great learning", "simplilearn", "masai school", "nxtwave",
            "coding ninjas", "coursera", "udemy", "edx", "pluralsight", "skillshare", "datacamp",
            "leetcode", "duolingo", "nta exam", "upsc", "ssc fee", "ibps", "gate exam",
            "cat exam", "icai", "school\\s*fees?", "college\\s*fees?", "university\\s*fees?", "tuition", "tution",
            "coaching", "academy", "institute", "semester\\s*fees?", "exam\\s*fees?", "admission\\s*fees?",
            "hostel\\s*fees?", "library\\s*fees?", "course\\s*fees?", "training\\s*fees?", "education", "schools?",
            "colleges?", "university", "vidyalaya", "gurukul", "preschool", "kindergarten",
            "daycare", "creche"
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
            "book\\s*my\\s*show", "bookmyshow", "bigtree\\s*(?:entertainment)?", "bms", "ticketnew",
            "paytm\\s*insider", "insider\\.in", "district", "pvr", "inox", "pvr\\s*inox", "cinepolis",
            "miraj\\s*cinemas?", "carnival\\s*cinemas?", "wave\\s*cinemas?", "imax", "cinemas?",
            "theatres?", "multiplex", "movie\\s*tickets?", "cinema\\s*tickets?", "theatre\\s*tickets?",
            "concert\\s*tickets?", "show\\s*tickets?", "movies?", "wonderla", "imagicaa?", "essel\\s*world",
            "smaaash", "play\\s*arena", "timezone", "mystery\\s*rooms", "trampoline\\s*park",
            "steam", "playstation", "sony\\s*interactive", "xbox", "nintendo", "epic\\s*games",
            "riot\\s*games", "blizzard", "ubisoft", "ea\\s*games", "krafton", "bgmi", "supercell",
            "roblox", "discord", "gaming\\s*zone", "arcade", "snooker", "bowling", "standup\\s*comedy"
        ),
        "Personal Care" to createRegex(
            "nykaa", "purplle", "sephora", "mamaearth", "minimalist", "lakme\\s*salon", "naturals\\s*salon", "enrich\\s*salon", "jawed\\s*habib",
            "salon", "saloon", "parlour", "parlor", "haircut", "barber", "spa", "grooming", "facial", "massage", "shave", "skincare", "cosmetics"
        ),
        "Education" to createRegex(
            "pens?", "ball\\s*pens?", "gel\\s*pens?", "fountain\\s*pens?", "ink\\s*pens?", "pilot\\s*pens?", "sketch\\s*pens?",
            "pencils?", "lead\\s*pencils?", "colour\\s*pencils?", "color\\s*pencils?",
            "stationery", "stationary", "staionery", "stationers?", "stationery\\s*mart", "stationery\\s*shop", "stationery\\s*store",
            "notebooks?", "notepads?", "registers?", "erasers?", "sharpeners?", "rulers?", "geometry\\s*box", "compass",
            "highlighters?", "markers?", "whiteboard\\s*markers?", "staplers?", "staple\\s*pins?", "fevicol", "glue\\s*stick", "paper\\s*clips?",
            "chart\\s*papers?", "a4\\s*sheets?", "a4\\s*papers?", "printer\\s*papers?", "drawing\\s*books?", "craft\\s*papers?",
            "book\\s*store", "book\\s*depot", "book\\s*stall", "book\\s*shop", "crossword\\s*bookstores?", "sapna\\s*book\\s*house", "higginbothams", "dc\\s*books",
            "xerox", "photostat", "printouts?", "spiral\\s*binding", "lamination",
            "classmate", "camlin", "kokuyo", "doms", "apsara", "nataraj", "reynolds", "flair\\s*(?:pen)?", "luxor", "uniball", "staedtler", "faber\\s*castell", "parker\\s*(?:pen)?",
            "tuition", "tution", "coaching", "academy", "vidyalaya", "vidyapeeth", "gurukul", "schools?", "colleges?", "university", "preschool", "kindergarten", "daycare",
            "school\\s*fees?", "college\\s*fees?", "tuition\\s*fees?", "tution\\s*fees?", "coaching\\s*fees?", "exam\\s*fees?", "admission\\s*fees?", "semester\\s*fees?", "hostel\\s*fees?",
            "physicswallah", "pw\\b", "allen\\s*(?:career)?", "aakash\\s*(?:institute)?", "fiitjee", "resonance", "pace\\s*iit", "sri\\s*chaitanya", "narayana\\s*educational",
            "brilliant\\s*pala", "xylem\\s*learning", "eduport", "entri\\s*app", "adda247", "testbook", "unacademy", "byjus?", "vedantu",
            "coursera", "udemy", "edx", "skillshare", "upgrad", "simplilearn", "great\\s*learning", "scaler\\s*academy", "coding\\s*ninjas", "geeksforgeeks", "leetcode",
            "upsc", "neet", "jee\\s*(?:main|advanced)", "gate\\s*exam", "cat\\s*exam", "clat", "ielts", "toefl", "gre\\s*exam", "gmat", "textbooks?", "study\\s*materials?"
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
            "apollo\\s*pharmacy", "apollo\\s*hospitals?", "pharmeasy", "tata\\s*1mg", "1mg", "netmeds", "medplus",
            "lal\\s*pathlabs", "thyrocare", "ddrc", "manipal\\s*hospital", "fortis", "amrita\\s*hospital",
            "rajagiri", "lakeshore", "baby\\s*memorial", "cultfit", "healthkart", "muscleblaze",
            "pharmacy", "medicals?", "medicine", "medicines", "tablets?", "syrup", "clinic", "hospital", "doctor",
            "dentist", "dental", "pathology", "diagnostics", "blood\\s*test", "lab\\s*test", "opticals?", "eye\\s*care", "scans?"
        ),
        "Investments" to createRegex(
            "zerodha", "groww", "upstox", "angel\\s*one", "paytm\\s*money", "indmoney", "kuvera",
            "smallcase", "geojit", "cams", "kfintech", "sbi\\s*mf", "hdfc\\s*mf", "parag\\s*parikh",
            "jar\\s*app", "gullak", "safegold", "ksfe", "mutual\\s*funds?", "sip", "stocks?", "shares?", "demat"
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

    data class Decision(
        val category: String,
        val confidence: Float,
        val source: DecisionSource,
        val explanation: String
    )

    enum class DecisionSource {
        USER_LEARNED_RULE,
        VPA_MEMORY,
        USER_NOTE,
        MCC_CODE,
        MCC_GATEWAY,
        MERCHANT_ENTITY,
        CONTEXTUAL_BEHAVIORAL,
        SEMANTIC_VECTOR,
        NAIVE_BAYES_ML,
        RAW_CONTENT_MATCH,
        SHOP_HEURISTIC,
        FALLBACK_UNSPECIFIED
    }

    private val categoryCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun clearCache() {
        categoryCache.clear()
        NaiveBayesCategorizer.invalidate()
    }

    /**
     * Unified multi-factor decision engine combining:
     * 1. User note & remarks (highest intent)
     * 2. User-learned rules & VPA memory
     * 3. ISO 18245 MCC code detection
     * 4. Commercial payment gateway handle decoding
     * 5. Known merchant entity matching
     * 6. Contextual & behavioral signals (amount, time of day, day of week, telco tiers, rent window)
     * 7. Machine Learning (Naive Bayes generalization from taught rules)
     * 8. Offline semantic vector centroid matching (cosine similarity)
     * 9. Raw narration scanning
     * 10. Generic shop-type and typo heuristics
     */
    /**
     * Synchronous decision engine combining:
     * 1. User note & remarks (highest intent)
     * 2. VPA memory
     * 3. ISO 18245 MCC code detection
     * 4. Commercial payment gateway handle decoding
     * 5. Known merchant entity matching
     * 6. Contextual & behavioral signals (amount, time of day, day of week, telco tiers, rent window)
     * 7. Offline semantic vector centroid matching (cosine similarity)
     * 8. Raw narration scanning
     * 9. Generic shop-type and typo heuristics
     */
    fun classifyDetailedSync(
        payee: String,
        rawContent: String = "",
        note: String? = null,
        itemsSummary: String? = null,
        amountPaise: Long? = null,
        timestamp: Long? = null
    ): Decision {
        val (basePayee, inlineNote) = PayeeHeuristics.splitPayeeAndInlineNote(payee)
        val cleanPayee = basePayee.replace('_', ' ').replace('/', ' ').trim()
        val generic = isGenericPayee(cleanPayee)
        val cleanContent = rawContent.replace('_', ' ').replace('/', ' ').trim()

        // 1. SMART LAYER: Evaluate user-written transaction note, inline payee note, or items summary first.
        val combinedNote = listOfNotNull(
            note?.takeIf { it.isNotBlank() },
            inlineNote?.takeIf { it.isNotBlank() },
            itemsSummary?.takeIf { it.isNotBlank() && it != note && it != inlineNote }
        ).joinToString(" ").trim()

        if (combinedNote.isNotBlank()) {
            val cleanNote = combinedNote.replace('_', ' ').replace('/', ' ').trim()
            val canonicalNote = PayeeHeuristics.canonicalize(cleanNote)
            for ((category, regex) in PAYEE_CATEGORY_REGEX_MAP) {
                if (regex.containsMatchIn(canonicalNote)) {
                    return Decision(category, 0.95f, DecisionSource.USER_NOTE, "Matched user note keyword: $cleanNote")
                }
            }
            PayeeHeuristics.categoryHint(cleanNote)?.let {
                return Decision(it, 0.88f, DecisionSource.USER_NOTE, "Shop/spelling hint on user note: $cleanNote")
            }
            SemanticCentroidClassifier.classifySemantic(cleanNote)?.let {
                return Decision(it.category, it.similarity, DecisionSource.SEMANTIC_VECTOR, "Semantic match on user note: ${it.explanation}")
            }
        }

        // 2. VPA memory lookup
        if (!generic) {
            SmsPatternLearner.lookupVpa(cleanPayee)?.category?.takeIf { !it.equals("Uncategorized", ignoreCase = true) && !it.equals("Unspecified", ignoreCase = true) }?.let {
                return Decision(it, 0.95f, DecisionSource.VPA_MEMORY, "Learned VPA memory for payee $cleanPayee")
            }
        }
        if (cleanContent.isNotBlank()) {
            SmsPatternLearner.lookupVpa(cleanContent)?.category?.takeIf { !it.equals("Uncategorized", ignoreCase = true) && !it.equals("Unspecified", ignoreCase = true) }?.let {
                return Decision(it, 0.92f, DecisionSource.VPA_MEMORY, "Learned VPA memory in raw narration")
            }
        }

        // 3. ISO 18245 MCC Code Detection in narration
        MccAndGatewayClassifier.extractMccMatch(rawContent)?.let {
            return Decision(it.category, 0.98f, DecisionSource.MCC_CODE, "ISO 18245 MCC ${it.mccCode} (${it.description})")
        }

        // 4. Commercial Payment Gateway Handle Decoding (e.g. bookmyshow@razorpay -> bookmyshow)
        val vpaCandidates = listOfNotNull(
            cleanPayee.takeIf { it.contains("@") },
            cleanContent.split(Regex("\\s+")).firstOrNull { it.contains("@") }
        )
        for (vpa in vpaCandidates) {
            val gw = MccAndGatewayClassifier.parseGatewayVpa(vpa)
            if (gw != null && gw.second) { // isGateway = true
                val gwHandle = PayeeHeuristics.canonicalize(gw.first)
                for ((category, regex) in PAYEE_CATEGORY_REGEX_MAP) {
                    if (regex.containsMatchIn(gwHandle)) {
                        return Decision(category, 0.90f, DecisionSource.MCC_GATEWAY, "Decoded commercial gateway handle @${gw.first}")
                    }
                }
            }
        }

        // 5. Known Merchant / Entity Regex on Payee
        val isPerson = !generic && PayeeHeuristics.looksLikePerson(cleanPayee)
        if (!generic) {
            val canonical = PayeeHeuristics.canonicalize(cleanPayee)
            for ((category, regex) in PAYEE_CATEGORY_REGEX_MAP) {
                if (regex.containsMatchIn(canonical)) {
                    return Decision(category, 0.90f, DecisionSource.MERCHANT_ENTITY, "Known merchant entity match: $cleanPayee")
                }
            }
        }

        // 6. Generic Shop-Type Words & Typo Heuristics on Payee (e.g. "Mahalakshmi Stores", "City Medicals", "Jk Foods")
        if (!generic && !isPerson) {
            PayeeHeuristics.categoryHint(cleanPayee)?.let {
                return Decision(it, 0.88f, DecisionSource.SHOP_HEURISTIC, "Generic shop type hint on payee: $cleanPayee")
            }
        }

        // 7. Contextual & Behavioral Signals (Amount, Time, Day, Telco plans, Rent window)
        ContextualClassifier.evaluate(
            amountPaise = amountPaise,
            timestamp = timestamp,
            payee = cleanPayee,
            note = note,
            rawContent = rawContent
        )?.let {
            return Decision(it.category, it.confidence, DecisionSource.CONTEXTUAL_BEHAVIORAL, it.explanation)
        }

        // 8. Semantic Subword Centroid Embedding (only if not a recognized personal name)
        val textToEmbed = listOfNotNull(
            cleanPayee.takeIf { !generic && !isPerson },
            cleanContent.takeIf { it.isNotBlank() && it != cleanPayee && (!isPerson || it.contains("@") || it.contains("/")) }
        ).joinToString(" ")
        if (textToEmbed.isNotBlank()) {
            SemanticCentroidClassifier.classifySemantic(textToEmbed)?.let {
                return Decision(it.category, it.similarity, DecisionSource.SEMANTIC_VECTOR, it.explanation)
            }
        }

        // 9. Raw Narration / Content Scan for unambiguous brand names
        if (cleanContent.isNotBlank() && cleanContent != cleanPayee) {
            val canonicalContent = PayeeHeuristics.canonicalize(cleanContent)
            for ((category, regex) in PAYEE_CATEGORY_REGEX_MAP) {
                if (regex.containsMatchIn(canonicalContent)) {
                    return Decision(category, 0.85f, DecisionSource.RAW_CONTENT_MATCH, "Narration keyword match")
                }
            }
            PayeeHeuristics.categoryHint(cleanContent)?.let {
                return Decision(it, 0.70f, DecisionSource.SHOP_HEURISTIC, "Generic shop type hint on narration")
            }
        }

        return Decision("Uncategorized", 0.0f, DecisionSource.FALLBACK_UNSPECIFIED, "No confident match found")
    }

    /**
     * Asynchronous full decision engine including learned database rules and Naive Bayes ML.
     */
    suspend fun classifyDetailed(
        payee: String,
        rawContent: String = "",
        note: String? = null,
        itemsSummary: String? = null,
        amountPaise: Long? = null,
        timestamp: Long? = null,
        ruleDao: CategoryRuleDao? = null
    ): Decision {
        val (basePayee, inlineNote) = PayeeHeuristics.splitPayeeAndInlineNote(payee)
        val cleanPayee = basePayee.replace('_', ' ').replace('/', ' ').trim()
        val generic = isGenericPayee(cleanPayee)
        val isPerson = !generic && PayeeHeuristics.looksLikePerson(cleanPayee)

        // 1. STRICT REMARKS / NOTES PRIORITY:
        // Remarks from bank statements or user notes reflect specific transaction intent and strictly override payee rules.
        val combinedNote = listOfNotNull(
            note?.takeIf { it.isNotBlank() },
            inlineNote?.takeIf { it.isNotBlank() },
            itemsSummary?.takeIf { it.isNotBlank() && it != note && it != inlineNote }
        ).joinToString(" ").trim()

        if (combinedNote.isNotBlank()) {
            val cleanNote = combinedNote.replace('_', ' ').replace('/', ' ').trim()
            val canonicalNote = PayeeHeuristics.canonicalize(cleanNote)
            for ((category, regex) in PAYEE_CATEGORY_REGEX_MAP) {
                if (regex.containsMatchIn(canonicalNote)) {
                    return Decision(category, 0.95f, DecisionSource.USER_NOTE, "Matched remark keyword: $cleanNote")
                }
            }
            PayeeHeuristics.categoryHint(cleanNote)?.let {
                return Decision(it, 0.88f, DecisionSource.USER_NOTE, "Shop/spelling hint on remark: $cleanNote")
            }
            SemanticCentroidClassifier.classifySemantic(cleanNote)?.let {
                return Decision(it.category, it.similarity, DecisionSource.SEMANTIC_VECTOR, "Semantic match on remark: ${it.explanation}")
            }
        }

        // 2. User Learned Rule: strictly applied to merchants, NEVER to individual contacts/persons!
        if (cleanPayee.isNotBlank() && !generic && !isPerson && ruleDao != null) {
            val learned = ruleDao.findMatchingCategory(cleanPayee.lowercase(Locale.ROOT))
            if (!learned.isNullOrBlank() && !learned.equals("Uncategorized", ignoreCase = true) && !learned.equals("Unspecified", ignoreCase = true)) {
                return Decision(learned, 1.0f, DecisionSource.USER_LEARNED_RULE, "Exact user-taught rule for $cleanPayee")
            }
        }

        // 3. Synchronous pipeline (MCC, gateway, regex, contextual, semantic, shop heuristics)
        val syncDecision = classifyDetailedSync(
            payee = payee,
            rawContent = rawContent,
            note = note,
            itemsSummary = itemsSummary,
            amountPaise = amountPaise,
            timestamp = timestamp
        )
        if (syncDecision.category != "Uncategorized" && syncDecision.category != "Unspecified") {
            return syncDecision
        }

        // 4. Machine Learning (Naive Bayes generalization from user-taught rules): only for merchants
        if (!generic && !isPerson && ruleDao != null) {
            NaiveBayesCategorizer.predict(cleanPayee.lowercase(Locale.ROOT), ruleDao)?.let {
                return Decision(it.category, it.confidence.toFloat(), DecisionSource.NAIVE_BAYES_ML, "Naive Bayes subword generalization")
            }
        }

        return syncDecision
    }

    fun classifySync(
        payee: String,
        rawContent: String = "",
        note: String? = null,
        itemsSummary: String? = null,
        amountPaise: Long? = null,
        timestamp: Long? = null
    ): String {
        return classifyDetailedSync(
            payee = payee,
            rawContent = rawContent,
            note = note,
            itemsSummary = itemsSummary,
            amountPaise = amountPaise,
            timestamp = timestamp
        ).category
    }

    suspend fun classify(
        payee: String,
        rawContent: String = "",
        ruleDao: CategoryRuleDao? = null,
        note: String? = null,
        itemsSummary: String? = null,
        amountPaise: Long? = null,
        timestamp: Long? = null
    ): String {
        val (basePayee, inlineNote) = PayeeHeuristics.splitPayeeAndInlineNote(payee)
        val cleanPayee = basePayee.replace('_', ' ').replace('/', ' ').trim().lowercase(Locale.ROOT)
        val hasSpecificNote = !note.isNullOrBlank() || !inlineNote.isNullOrBlank() || !itemsSummary.isNullOrBlank()
        val isPerson = !isGenericPayee(cleanPayee) && PayeeHeuristics.looksLikePerson(cleanPayee)

        val canUseCache = cleanPayee.isNotBlank() && !isGenericPayee(cleanPayee) && !hasSpecificNote && !isPerson
        if (canUseCache) {
            categoryCache[cleanPayee]?.let { return it }
        }

        val decision = classifyDetailed(
            payee = payee,
            rawContent = rawContent,
            note = note,
            itemsSummary = itemsSummary,
            amountPaise = amountPaise,
            timestamp = timestamp,
            ruleDao = ruleDao
        )

        if (canUseCache && decision.category != "Uncategorized" && decision.category != "Unspecified") {
            categoryCache[cleanPayee] = decision.category
        }

        return decision.category
    }
}