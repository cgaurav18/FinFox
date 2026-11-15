package com.mymoney.app.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mymoney.app.core.FinanceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** A snackbar message; [success] ones get the animated confirmation tick. */
data class UiNote(val text: String, val success: Boolean = false)

/** App-wide snackbar messages emitted by ViewModels and shown by the root scaffold. */
@Singleton
class MessageBus @Inject constructor() {
    private val channel = Channel<UiNote>(Channel.BUFFERED)
    val messages = channel.receiveAsFlow()
    fun show(text: String) { channel.trySend(UiNote(text)) }
    fun success(text: String) { channel.trySend(UiNote(text, success = true)) }
}

val LocalCurrency = compositionLocalOf { "INR" }

abstract class BaseViewModel(protected val bus: MessageBus) : ViewModel() {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** One-shot "operation finished" signal (e.g. pop the screen after save). */
    private val _done = Channel<Unit>(Channel.CONFLATED)
    val done = _done.receiveAsFlow()

    protected fun finish() { _done.trySend(Unit) }

    /** Runs a repository operation, surfacing rule violations as snackbars instead of crashes. */
    protected fun launchSafe(success: String? = null, finishOnSuccess: Boolean = false, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
                success?.let(bus::success)
                if (finishOnSuccess) finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: FinanceException) {
                bus.show(e.message ?: "Couldn't complete that")
            } catch (e: com.mymoney.app.backup.BackupException) {
                bus.show(e.message ?: "Backup failed")
            } catch (e: com.mymoney.app.cloud.CloudBackupException) {
                bus.show(e.message ?: "Google Drive backup failed")
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                bus.show("This record is linked to other data and can't be changed that way")
            } catch (e: Exception) {
                bus.show("Something went wrong: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _busy.value = false
            }
        }
    }
}
