package com.mymoney.app.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.ListRow
import com.mymoney.app.ui.navigation.Routes
import com.mymoney.app.ui.theme.FinFoxTheme

private data class MoreItem(val title: String, val subtitle: String, val icon: ImageVector, val color: Color, val route: String)

@Composable
fun MoreScreen(nav: NavController) {
    val fin = FinFoxTheme.colors
    val items = listOf(
        MoreItem("Scan & Pay", "Pay any UPI QR and record it automatically", Icons.Filled.QrCodeScanner, fin.income, Routes.pay(scan = true)),
        MoreItem("Receive money", "Your UPI IDs as QR codes to get paid", Icons.Filled.QrCode2, MaterialTheme.colorScheme.primary, Routes.RECEIVE),
        MoreItem("Auto-capture payments", "Record payments from bank SMS & UPI apps", Icons.Filled.AutoAwesome, MaterialTheme.colorScheme.primary, Routes.AUTO_CAPTURE),
        MoreItem("Accounts", "Bank accounts, credit cards & cash", Icons.Filled.AccountBalance, MaterialTheme.colorScheme.primary, Routes.ACCOUNTS),
        MoreItem("Scheduled Payments", "EMI, SIP, bills & subscriptions", Icons.Filled.EventRepeat, fin.warning, Routes.SCHEDULES),
        MoreItem("Money with People", "Money you lent or borrowed", Icons.Filled.Groups, fin.transfer, Routes.PEOPLE),
        MoreItem("Budgets", "Monthly category limits", Icons.Filled.PieChart, fin.accent, Routes.BUDGETS),
        MoreItem("Savings Goals", "Save towards what matters", Icons.Filled.Savings, fin.savings, Routes.GOALS),
        MoreItem("Settings", "Profile, security, backup & more", Icons.Filled.Settings, MaterialTheme.colorScheme.onSurfaceVariant, Routes.SETTINGS),
    )
    AppScaffold(title = "More") { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AppCard(contentPadding = 0.dp) {
                    items.forEachIndexed { i, it ->
                        ListRow(it.icon, it.color, it.title, it.subtitle, onClick = { nav.navigate(it.route) }) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (i < items.lastIndex) HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}
