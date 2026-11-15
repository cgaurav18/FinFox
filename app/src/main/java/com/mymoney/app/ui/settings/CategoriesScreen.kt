package com.mymoney.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.data.db.CategoryEntity
import com.mymoney.app.data.db.CategoryType
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppIcons
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.AppTextField
import com.mymoney.app.ui.components.Chip
import com.mymoney.app.ui.components.ColorPicker
import com.mymoney.app.ui.components.ConfirmDialog
import com.mymoney.app.ui.components.IconPicker
import com.mymoney.app.ui.components.ListRow
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.PillTabs

@Composable
fun CategoriesScreen(nav: NavController, vm: CategoriesViewModel = hiltViewModel()) {
    val type by vm.type.collectAsStateWithLifecycle()
    val all by vm.all.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CategoryEntity?>(null) }
    AppScaffold(
        title = "Categories", onBack = { nav.popBackStack() },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add Category") },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        },
    ) { pad ->
        val list = all?.filter { it.type == type } ?: run { LoadingState(Modifier.padding(pad)); return@AppScaffold }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { PillTabs(CategoryType.entries, type, { if (it == CategoryType.EXPENSE) "Expense" else "Income" }, { vm.type.value = it }) }
            item {
                AppCard(contentPadding = 0.dp) {
                    list.sortedBy { it.archived }.forEach { c ->
                        var menu by remember { mutableStateOf(false) }
                        ListRow(AppIcons.forKey(c.iconKey), Color(c.colorArgb), c.name, if (c.isSystem) "Built-in" else "Custom", onClick = { editing = c }) {
                            if (c.archived) Chip("Archived", MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Options for ${c.name}") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text(if (c.archived) "Restore" else "Archive") }, { menu = false; vm.setArchived(c, !c.archived) }, leadingIcon = { Icon(if (c.archived) Icons.Filled.Unarchive else Icons.Filled.Archive, null) })
                                if (!c.isSystem) DropdownMenuItem({ Text("Delete") }, { menu = false; deleting = c }, leadingIcon = { Icon(Icons.Filled.Delete, null) })
                            }
                        }
                    }
                }
            }
            item { Text("Archived categories are hidden when adding transactions but kept for history and reports.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (adding || editing != null) CategoryDialog(editing, { adding = false; editing = null }) { name, icon, color -> vm.save(editing?.id, name, icon, color) }
    deleting?.let { c -> ConfirmDialog("Delete ${c.name}?", "Categories used by transactions can't be deleted — archive them instead.", "Delete", { vm.delete(c) }, { deleting = null }) }
}

@Composable
private fun CategoryDialog(existing: CategoryEntity?, onDismiss: () -> Unit, onSave: (String, String, Long) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var icon by remember { mutableStateOf(existing?.iconKey ?: "other") }
    var color by remember { mutableStateOf(existing?.colorArgb ?: 0xFF7C3AED) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New category" else "Edit category") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTextField(name, { name = it }, "Name", capitalization = KeyboardCapitalization.Words, maxLength = 30)
                Text("Icon", style = MaterialTheme.typography.labelLarge)
                IconPicker(AppIcons.categoryKeys.keys.toList(), icon, Color(color)) { icon = it }
                Text("Colour", style = MaterialTheme.typography.labelLarge)
                ColorPicker(color, { color = it })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, icon, color); onDismiss() }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
