package jmotley.com.jspades.models

import androidx.lifecycle.ViewModel
import jmotley.com.jspades.data.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class RenegeJokesUiState(
    val items: List<String> = emptyList()
)

class RenegeJokesViewModel : ViewModel() {
    private val _state = MutableStateFlow(RenegeJokesUiState(items = Constants.RENEGE_JOKES))
    val state: StateFlow<RenegeJokesUiState> = _state
}
