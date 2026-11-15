package com.mymoney.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mymoney.app.ui.accounts.AccountDetailScreen
import com.mymoney.app.ui.accounts.AccountsScreen
import com.mymoney.app.ui.accounts.AddAccountScreen
import com.mymoney.app.ui.auth.LockScreen
import com.mymoney.app.ui.auth.OnboardingFlow
import com.mymoney.app.ui.auth.SplashContent
import com.mymoney.app.ui.budgets.BudgetsScreen
import com.mymoney.app.ui.components.LocalCurrency
import com.mymoney.app.ui.goals.AddGoalScreen
import com.mymoney.app.ui.goals.GoalDetailScreen
import com.mymoney.app.ui.goals.GoalsScreen
import com.mymoney.app.ui.home.HomeScreen
import com.mymoney.app.ui.more.MoreScreen
import com.mymoney.app.ui.people.AddLoanScreen
import com.mymoney.app.ui.people.LoanDetailScreen
import com.mymoney.app.ui.people.PeopleScreen
import com.mymoney.app.ui.people.PersonScreen
import com.mymoney.app.ui.reports.ReportsScreen
import com.mymoney.app.ui.schedules.AddScheduleScreen
import com.mymoney.app.ui.schedules.ScheduleDetailScreen
import com.mymoney.app.ui.schedules.SchedulesScreen
import com.mymoney.app.ui.settings.BackupScreen
import com.mymoney.app.ui.settings.CategoriesScreen
import com.mymoney.app.ui.settings.ChangePinScreen
import com.mymoney.app.ui.settings.SettingsScreen
import com.mymoney.app.ui.theme.MyMoneyTheme
import com.mymoney.app.ui.account.GoogleAccountScreen
import com.mymoney.app.ui.cloud.BackupHistoryScreen
import com.mymoney.app.ui.cloud.DriveConnectionScreen
import com.mymoney.app.ui.cloud.RestoreScreen
import com.mymoney.app.ui.notifications.NotificationCenterScreen
import com.mymoney.app.ui.notifications.NotificationPreferencesScreen
import com.mymoney.app.ui.widgets.WidgetGalleryScreen
import com.mymoney.app.ui.transactions.AddTransactionScreen
import com.mymoney.app.ui.transactions.TransactionDetailScreen
import com.mymoney.app.ui.transactions.TransactionsScreen

@Composable
fun AppRoot(vm: RootViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()
    MyMoneyTheme(state.theme) {
        CompositionLocalProvider(LocalCurrency provides state.currency) {
            Crossfade(targetState = state.phase, label = "root") { phase ->
                when (phase) {
                    RootPhase.SPLASH -> SplashContent()
                    RootPhase.ONBOARDING -> OnboardingFlow(onFinished = vm::onboardingFinished)
                    RootPhase.MAIN -> Box(Modifier.fillMaxSize()) {
                        MainScaffold(vm, locked)
                        // The app stays composed under the lock so navigation state is preserved.
                        AnimatedVisibility(locked, enter = fadeIn(), exit = fadeOut()) { LockScreen() }
                    }
                }
            }
        }
    }
}

private data class BottomItem(val route: String, val nav: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

@Composable
private fun MainScaffold(vm: RootViewModel, locked: Boolean) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.bus.messages.collect { snackbar.showSnackbar(it) } }
    val pending by vm.pendingRoute.collectAsStateWithLifecycle()
    LaunchedEffect(pending, locked) {
        val r = pending
        if (r != null && !locked) { nav.navigate(r) { launchSingleTop = true }; vm.consumePendingRoute() }
    }
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val items = listOf(
        BottomItem(Routes.HOME, Routes.HOME, "Home", Icons.Outlined.Home, Icons.Filled.Home),
        BottomItem(Routes.TXNS, Routes.txns(), "Txns", Icons.AutoMirrored.Outlined.ReceiptLong, Icons.AutoMirrored.Filled.ReceiptLong),
        BottomItem(Routes.REPORTS, Routes.REPORTS, "Reports", Icons.Outlined.BarChart, Icons.Filled.BarChart),
        BottomItem(Routes.MORE, Routes.MORE, "More", Icons.Outlined.GridView, Icons.Filled.GridView),
    )
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            AnimatedVisibility(current in Routes.topLevel, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                Box {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        items.forEachIndexed { i, item ->
                            if (i == 2) NavigationBarItem(selected = false, onClick = {}, enabled = false, icon = {}, label = {})
                            val sel = current == item.route
                            NavigationBarItem(
                                selected = sel,
                                onClick = {
                                    nav.navigate(item.nav) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(if (sel) item.selectedIcon else item.icon, null) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                    FloatingActionButton(
                        onClick = { nav.navigate(Routes.txnEdit()) },
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.primary,
                        elevation = FloatingActionButtonDefaults.elevation(6.dp),
                        modifier = Modifier.align(Alignment.TopCenter).offset(y = (-14).dp).size(60.dp).semantics { contentDescription = "Add transaction" },
                    ) { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp)) }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Box(Modifier.padding(bottom = pad.calculateBottomPadding())) { AppNavHost(nav) }
    }
}

private fun idArg(name: String = "id") = navArgument(name) { type = NavType.LongType; defaultValue = -1L }
private fun strArg(name: String) = navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null }

@Composable
private fun AppNavHost(nav: NavHostController) {
    NavHost(
        navController = nav, startDestination = Routes.HOME,
        enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() },
    ) {
        composable(Routes.HOME, enterTransition = { fadeIn() }, popExitTransition = { fadeOut() }) { HomeScreen(nav) }
        composable(Routes.TXNS, arguments = listOf(strArg("type")), enterTransition = { fadeIn() }, popExitTransition = { fadeOut() }) { TransactionsScreen(nav) }
        composable(Routes.REPORTS, enterTransition = { fadeIn() }, popExitTransition = { fadeOut() }) { ReportsScreen(nav) }
        composable(Routes.MORE, enterTransition = { fadeIn() }, popExitTransition = { fadeOut() }) { MoreScreen(nav) }
        composable(Routes.TXN_EDIT, arguments = listOf(idArg(), strArg("type"))) { AddTransactionScreen(nav) }
        composable(Routes.TXN_DETAIL, arguments = listOf(idArg())) { TransactionDetailScreen(nav) }
        composable(Routes.ACCOUNTS) { AccountsScreen(nav) }
        composable(Routes.ACCOUNT_EDIT, arguments = listOf(idArg(), strArg("type"))) { AddAccountScreen(nav) }
        composable(Routes.ACCOUNT_DETAIL, arguments = listOf(idArg())) { AccountDetailScreen(nav) }
        composable(Routes.SCHEDULES) { SchedulesScreen(nav) }
        composable(Routes.SCHEDULE_EDIT, arguments = listOf(idArg())) { AddScheduleScreen(nav) }
        composable(Routes.SCHEDULE_DETAIL, arguments = listOf(idArg(), navArgument("pay") { type = NavType.BoolType; defaultValue = false })) { ScheduleDetailScreen(nav) }
        composable(Routes.PEOPLE) { PeopleScreen(nav) }
        composable(Routes.LOAN_EDIT, arguments = listOf(idArg(), strArg("direction"), strArg("person"))) { AddLoanScreen(nav) }
        composable(Routes.LOAN_DETAIL, arguments = listOf(idArg())) { LoanDetailScreen(nav) }
        composable(Routes.PERSON, arguments = listOf(idArg())) { PersonScreen(nav) }
        composable(Routes.BUDGETS) { BudgetsScreen(nav) }
        composable(Routes.GOALS) { GoalsScreen(nav) }
        composable(Routes.GOAL_EDIT, arguments = listOf(idArg())) { AddGoalScreen(nav) }
        composable(Routes.GOAL_DETAIL, arguments = listOf(idArg())) { GoalDetailScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(Routes.CATEGORIES) { CategoriesScreen(nav) }
        composable(Routes.CHANGE_PIN) { ChangePinScreen(nav) }
        composable(Routes.BACKUP) { BackupScreen(nav) }
        composable(Routes.GOOGLE_ACCOUNT) { GoogleAccountScreen(nav) }
        composable(Routes.DRIVE) { DriveConnectionScreen(nav) }
        composable(Routes.BACKUP_HISTORY) { BackupHistoryScreen(nav) }
        composable(Routes.RESTORE, arguments = listOf(strArg("fileId"), strArg("uri"))) { RestoreScreen(nav) }
        composable(Routes.WIDGETS) { WidgetGalleryScreen(nav) }
        composable(Routes.NOTIFICATIONS) { NotificationCenterScreen(nav) }
        composable(Routes.NOTIFICATION_PREFS) { NotificationPreferencesScreen(nav) }
    }
}
