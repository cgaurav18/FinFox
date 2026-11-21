package com.mymoney.app.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mymoney.app.autocapture.UpiUri
import com.mymoney.app.core.FinanceException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One of the user's own UPI IDs, shown as a "Receive money" QR code. */
@Serializable
data class MyUpiId(val vpa: String, val name: String, val label: String = "")

/** The user's saved UPI IDs (first = default). Kept on the device only; they're not secret, just preferences. */
class MyUpiRepository(private val store: DataStore<Preferences>) {
    private val key = stringPreferencesKey("my_upi_ids")
    private val json = Json { ignoreUnknownKeys = true }

    val ids: Flow<List<MyUpiId>> = store.data.map { p ->
        p[key]?.let { runCatching { json.decodeFromString<List<MyUpiId>>(it) }.getOrNull() }.orEmpty()
    }

    private suspend fun write(list: List<MyUpiId>) { store.edit { it[key] = json.encodeToString(list) } }

    suspend fun add(vpa: String, name: String, label: String) {
        val v = vpa.trim().lowercase()
        if (!UpiUri.isValidVpa(v)) throw FinanceException("Enter a UPI ID like name@okbank")
        if (name.isBlank()) throw FinanceException("Enter the name people should see")
        val list = ids.first()
        if (list.any { it.vpa == v }) throw FinanceException("That UPI ID is already saved")
        write(list + MyUpiId(v, name.trim().take(50), label.trim().take(30)))
    }

    suspend fun remove(vpa: String) = write(ids.first().filterNot { it.vpa == vpa })

    /** Moves [vpa] to the front: the QR shown first. */
    suspend fun makeDefault(vpa: String) {
        val list = ids.first()
        val item = list.firstOrNull { it.vpa == vpa } ?: return
        write(listOf(item) + list.filterNot { it.vpa == vpa })
    }
}
