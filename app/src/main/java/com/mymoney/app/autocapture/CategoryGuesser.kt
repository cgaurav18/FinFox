package com.mymoney.app.autocapture

/**
 * First-guess category names for well-known Indian merchants. Names match the default categories in
 * DefaultData; once the user corrects a category for a merchant, that learned rule wins over this list.
 */
object CategoryGuesser {
    private val expense: List<Pair<String, List<String>>> = listOf(
        "Groceries" to listOf("blinkit", "zepto", "bigbasket", "big basket", "dmart", "d mart", "instamart", "grofers", "jiomart", "ratnadeep",
            "more retail", "kirana", "grocery", "supermarket", "fresh", "milk", "dairy", "vegetable", "fruits", "spencer", "nature's basket", "bbnow"),
        "Food" to listOf("swiggy", "zomato", "restaurant", "cafe", "café", "dominos", "domino's", "mcdonald", "kfc", "pizza", "starbucks",
            "eatsure", "bakery", "burger", "biryani", "chai", "tea", "coffee", "dhaba", "hotel", "food", "canteen", "haldiram", "subway"),
        "Shopping" to listOf("amazon", "flipkart", "myntra", "ajio", "meesho", "nykaa", "croma", "reliance digital", "decathlon", "tata cliq",
            "lifestyle", "pantaloons", "westside", "zara", "h&m", "ikea", "mall", "store", "mart", "shop"),
        "Fuel" to listOf("petrol", "fuel", "hpcl", "bpcl", "iocl", "indian oil", "indianoil", "shell", "bharat petroleum", "hp pay", "filling station", "cng"),
        "Transport" to listOf("uber", "ola", "rapido", "metro", "namma yatri", "fastag", "parking", "toll", "auto", "cab", "bmtc", "best bus", "yulu"),
        "Travel" to listOf("irctc", "makemytrip", "goibibo", "indigo", "air india", "akasa", "vistara", "spicejet", "redbus", "ixigo", "cleartrip",
            "oyo", "airbnb", "yatra", "easemytrip", "booking.com", "agoda"),
        "Bills" to listOf("airtel", "jio", "vodafone", "vi ", "bsnl", "electricity", "bescom", "msedcl", "tata power", "adani", "torrent power",
            "broadband", "act fibernet", "hathway", "gas", "indane", "bharat gas", "recharge", "dth", "tata play", "water", "postpaid", "prepaid",
            "billdesk", "bill payment", "bbps"),
        "Entertainment" to listOf("netflix", "hotstar", "jiocinema", "prime video", "spotify", "bookmyshow", "pvr", "inox", "youtube", "sonyliv",
            "zee5", "gaana", "steam", "playstation", "district"),
        "Medical" to listOf("pharmacy", "apollo", "medplus", "1mg", "pharmeasy", "hospital", "clinic", "netmeds", "chemist", "medical", "diagnostic",
            "lab", "dr ", "doctor", "health"),
        "Education" to listOf("udemy", "coursera", "byju", "school", "college", "university", "fees", "tuition", "unacademy", "vedantu", "books"),
        "EMI" to listOf("emi", "loan", "bajaj finance", "bajaj finserv", "home credit", "nach", "ach debit"),
        "Rent" to listOf("rent", "nobroker", "nestaway", "housing"),
    )

    private val income: List<Pair<String, List<String>>> = listOf(
        "Salary" to listOf("salary", "sal ", "payroll", "wages"),
        "Interest" to listOf("interest", "int.pd", "int pd", "int. credit"),
        "Refund" to listOf("refund", "reversal", "reversed", "cashback", "chargeback"),
        "Business" to listOf("settlement", "payout"),
    )

    /** Returns the name of the best default category for [text] (merchant + message), or null. */
    fun guess(text: String, direction: TxnDirection): String? {
        val t = " " + text.lowercase() + " "
        val table = if (direction == TxnDirection.DEBIT) expense else income
        return table.firstOrNull { (_, words) -> words.any { w -> matches(t, w) } }?.first
    }

    private fun matches(t: String, w: String): Boolean {
        // Short words must stand alone ("ola" must not match "cola"); longer ones may be part of a handle like "swiggyupi".
        if (w.length <= 4) return Regex("""(?<![a-z])${Regex.escape(w.trim())}(?![a-z])""").containsMatchIn(t)
        return t.contains(w)
    }

    /** Stable key for learning a merchant's category: the UPI handle when known, else the cleaned name. */
    fun merchantKey(vpa: String?, name: String?): String? {
        vpa?.trim()?.lowercase()?.takeIf { it.contains('@') }?.let { return it }
        val n = name?.lowercase()?.replace(Regex("""[^a-z0-9]+"""), " ")?.trim()
        return n?.takeIf { it.length >= 2 }
    }
}
