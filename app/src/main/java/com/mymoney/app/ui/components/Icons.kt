package com.mymoney.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Train
import androidx.compose.ui.graphics.vector.ImageVector
import com.mymoney.app.data.db.AccountType

/** Icon keys are persisted; this maps them to Material icons. */
object AppIcons {
    val categoryKeys: Map<String, ImageVector> = linkedMapOf(
        "food" to Icons.Filled.Restaurant, "shopping" to Icons.Filled.ShoppingBag, "groceries" to Icons.Filled.LocalGroceryStore,
        "fuel" to Icons.Filled.LocalGasStation, "transport" to Icons.Filled.DirectionsCar, "rent" to Icons.Filled.Home,
        "emi" to Icons.Filled.CreditCard, "bills" to Icons.AutoMirrored.Filled.ReceiptLong, "medical" to Icons.Filled.LocalHospital,
        "entertainment" to Icons.Filled.Movie, "education" to Icons.Filled.School, "travel" to Icons.Filled.Flight,
        "salary" to Icons.Filled.Payments, "freelance" to Icons.Filled.Work, "business" to Icons.Filled.BusinessCenter,
        "interest" to Icons.Filled.Percent, "refund" to Icons.AutoMirrored.Filled.Undo, "gift" to Icons.Filled.CardGiftcard,
        "cafe" to Icons.Filled.LocalCafe, "fitness" to Icons.Filled.FitnessCenter, "clothing" to Icons.Filled.Checkroom,
        "pets" to Icons.Filled.Pets, "kids" to Icons.Filled.ChildCare, "repair" to Icons.Filled.Build, "beauty" to Icons.Filled.Spa,
        "games" to Icons.Filled.SportsEsports, "train" to Icons.Filled.Train, "invest" to Icons.AutoMirrored.Filled.TrendingUp,
        "other" to Icons.Filled.Category,
    )

    private val extra: Map<String, ImageVector> = mapOf(
        "sip" to Icons.Filled.ShowChart, "electricity" to Icons.Filled.Bolt, "internet" to Icons.Filled.Wifi,
        "mobile" to Icons.Filled.PhoneAndroid, "insurance" to Icons.Filled.HealthAndSafety, "credit_card" to Icons.Filled.CreditCard,
        "subscription" to Icons.Filled.Subscriptions, "bank" to Icons.Filled.AccountBalance, "cash" to Icons.Filled.AccountBalanceWallet,
        "savings" to Icons.Filled.Savings,
    )

    fun forKey(key: String?): ImageVector = categoryKeys[key] ?: extra[key] ?: Icons.Filled.Category

    fun forAccount(type: AccountType): ImageVector = when (type) {
        AccountType.BANK -> Icons.Filled.AccountBalance
        AccountType.CREDIT_CARD -> Icons.Filled.CreditCard
        AccountType.CASH -> Icons.Filled.AccountBalanceWallet
    }

    /** Colours users can pick for accounts, categories and goals: muted, calm hues that sit well on the sage theme. */
    val palette: List<Long> = listOf(
        0xFF2E6F68, 0xFF3F8A82, 0xFF4F7CAC, 0xFF6C7FB8, 0xFF8A6FB0, 0xFFB46A8C, 0xFFD06A5A,
        0xFFC98A3D, 0xFFB59A3A, 0xFF5E9C5A, 0xFF4E9AA8, 0xFF64748B,
    )
    /** Default colour for new accounts / goals / categories: the brand teal. */
    const val DEFAULT_COLOR: Long = 0xFF2E6F68
}
