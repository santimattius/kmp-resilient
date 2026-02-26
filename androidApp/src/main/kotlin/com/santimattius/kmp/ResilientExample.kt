package com.santimattius.kmp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ResilientExample(
    viewModel: ResilientViewModel = viewModel {
        ResilientViewModel()
    }
) {
    val uiState by viewModel.uiState.collectAsState()
    val resourceId by viewModel.resourceId.collectAsState()

    MaterialTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer)
                .safeContentPadding()
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(painterResource(R.drawable.compose_multiplatform), null)
            }

            Text("Cache key: demo:$resourceId (keyProvider)")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("default", "a", "b").forEach { id ->
                    Button(
                        onClick = { viewModel.setResourceId(id) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(id)
                    }
                }
            }

            Button(
                onClick = { viewModel.executePolicy() },
                enabled = !uiState.isLoading
            ) {
                Text("Run resilient call")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Button(
                    onClick = { viewModel.invalidateCache() },
                    enabled = !uiState.isLoading,
                ) {
                    Text("Invalidate current")
                }
                Button(
                    onClick = { viewModel.invalidateCachePrefix() },
                    enabled = !uiState.isLoading,
                ) {
                    Text("Invalidate all (prefix)")
                }
            }

            if (uiState.isLoading) {
                CircularProgressIndicator()
            }

            uiState.result?.let {
                Text("Result: $it")
            }

            uiState.error?.let {
                Text("Error: $it")
            }

            if (uiState.events.isNotEmpty()) {
                Text("Events:")
                uiState.events.forEach {
                    Text(it)
                }
            }
        }
    }
}

@Composable
@Preview
fun ResilientExamplePreview() {
    ResilientExample()
}
