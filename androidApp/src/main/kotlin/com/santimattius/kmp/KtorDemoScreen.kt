package com.santimattius.kmp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun KtorDemoScreen(
    viewModel: KtorDemoViewModel = viewModel { KtorDemoViewModel() }
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    MaterialTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer)
                .safeContentPadding()
                .fillMaxSize()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Ktor Plugin Demo", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "HttpClient with ResilientPlugin — retry on 5xx via shouldRetryResult",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "Simulated sequence: 503 → 503 → 200",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Plugin config: retry { maxAttempts = 3 } + shouldRetryResult on HTTP 5xx",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { viewModel.runKtorCall() },
                enabled = !uiState.isLoading,
            ) {
                Text("Run Ktor call")
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.isLoading) {
                CircularProgressIndicator()
            }

            uiState.result?.let {
                Text("Result: $it", modifier = Modifier.fillMaxWidth())
            }

            uiState.error?.let {
                Text("Error: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
            }

            if (uiState.events.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Events:", style = MaterialTheme.typography.titleSmall)
                uiState.events.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
