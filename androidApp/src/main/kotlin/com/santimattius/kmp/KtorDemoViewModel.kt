package com.santimattius.kmp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.santimattius.resilient.composition.resilient
import com.santimattius.resilient.ktor.ResilientPlugin
import com.santimattius.resilient.retry.RetryableResultException
import com.santimattius.resilient.telemetry.ResilientEvent
import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KtorDemoUiState(
    val isLoading: Boolean = false,
    val result: String? = null,
    val error: String? = null,
    val events: List<String> = emptyList(),
)

class KtorDemoViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(KtorDemoUiState())
    val uiState: StateFlow<KtorDemoUiState> = _uiState.asStateFlow()

    // BYO policy — gives us access to policy.events to display in the UI.
    // shouldRetryResult inspects the HttpClientCall status (no expectSuccess required).
    private val ktorPolicy = viewModelScope.resilient {
        retry {
            maxAttempts = 3
            shouldRetryResult = { result ->
                (result as? HttpClientCall)?.response?.status?.value?.let { it >= 500 } ?: false
            }
        }
    }

    // Response sequence resets on each "Run" click: 503, 503, 200
    private var callIndex = 0
    private val statusSequence = listOf(503, 503, 200)

    private val mockEngine = MockEngine { _ ->
        val status = statusSequence.getOrElse(callIndex++) { 200 }
        if (status == 200) {
            respond(
                content = """{"status":"ok","message":"Resilient Ktor call succeeded!"}""",
                status = HttpStatusCode.OK,
            )
        } else {
            respond(content = "", status = HttpStatusCode.fromValue(status))
        }
    }

    private val client = HttpClient(mockEngine) {
        install(ResilientPlugin) {
            policy = ktorPolicy
        }
    }

    init {
        viewModelScope.launch {
            ktorPolicy.events.collect { event ->
                val label = formatEvent(event)
                _uiState.update { it.copy(events = (it.events + label).takeLast(12)) }
            }
        }
    }

    fun runKtorCall() {
        viewModelScope.launch {
            callIndex = 0
            _uiState.update { KtorDemoUiState(isLoading = true) }
            try {
                val response = client.get("https://demo.local/api")
                val body = response.bodyAsText()
                _uiState.update {
                    it.copy(isLoading = false, result = "HTTP ${response.status.value}: $body")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "${e::class.simpleName}: ${e.message}",
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        client.close()
        ktorPolicy.close()
    }

    private fun formatEvent(event: ResilientEvent): String = when (event) {
        is ResilientEvent.RetryAttempt -> {
            val statusCode = (event.error as? RetryableResultException)
                ?.let { it.lastValue as? HttpClientCall }
                ?.response?.status?.value
            "Retry #${event.attempt}" + if (statusCode != null) " (HTTP $statusCode)" else ""
        }
        is ResilientEvent.OperationSuccess -> "Success in ${event.duration}"
        is ResilientEvent.OperationFailure -> "Failed: ${event.error.message}"
        else -> event::class.simpleName ?: "Event"
    }
}
