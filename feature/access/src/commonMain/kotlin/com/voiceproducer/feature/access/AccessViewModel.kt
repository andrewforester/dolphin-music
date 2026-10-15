package com.voiceproducer.feature.access

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceproducer.core.domain.access.AccessResult
import com.voiceproducer.core.domain.access.AccessStore
import com.voiceproducer.core.domain.usecase.SubmitAccessCodeUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccessUiState(
    val granted: Boolean = false,
    val sheetOpen: Boolean = false,
    val code: String = "",
    val checking: Boolean = false,
    val error: String? = null,
)

sealed interface AccessIntent {
    data class CodeChanged(
        val code: String,
    ) : AccessIntent

    data object Submit : AccessIntent

    data object Dismiss : AccessIntent
}

class AccessViewModel(
    store: AccessStore,
    private val submitCode: SubmitAccessCodeUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(AccessUiState())
    val state: StateFlow<AccessUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            store.access.collect { access ->
                val granted = access != null
                _state.update { it.copy(granted = granted, sheetOpen = !granted) }
            }
        }
    }

    fun onIntent(intent: AccessIntent) {
        when (intent) {
            is AccessIntent.CodeChanged -> _state.update { it.copy(code = intent.code, error = null) }
            AccessIntent.Submit -> submit()
            AccessIntent.Dismiss -> _state.update { it.copy(sheetOpen = false) }
        }
    }

    fun guard(proceed: () -> Unit) {
        if (_state.value.granted) proceed() else _state.update { it.copy(sheetOpen = true) }
    }

    private fun submit() {
        val current = _state.value
        if (current.checking) return
        _state.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            val error =
                when (val result = submitCode(current.code)) {
                    AccessResult.Granted -> null
                    AccessResult.Rejected -> REJECTED
                    is AccessResult.Failed -> "Couldn't check the code: ${result.reason}"
                }
            _state.update {
                if (error == null) it.copy(checking = false, code = "") else it.copy(checking = false, error = error)
            }
        }
    }

    private companion object {
        const val REJECTED = "That code didn't work. Check it and try again."
    }
}
