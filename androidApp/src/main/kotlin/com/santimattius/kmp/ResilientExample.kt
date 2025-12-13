package com.santimattius.kmp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import com.santimattius.resilient.composition.ResilientScope
import com.santimattius.resilient.composition.resilient
import com.santimattius.resilient.retry.ExponentialBackoff
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Composable
@Preview
fun ResilientExample() {
    MaterialTheme {
        val result = remember { mutableStateOf<String?>(null) }
        val error = remember { mutableStateOf<String?>(null) }
        val events = remember { mutableStateListOf<String>() }
        val runTrigger = remember { mutableIntStateOf(0) }

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
            Button(onClick = {
                result.value = null
                error.value = null
                events.clear()
                runTrigger.intValue++
            }) {
                Text("Run resilient call")
            }

            result.value?.let { Text("Result: $it") }
            error.value?.let { Text("Error: $it") }

            if (events.isNotEmpty()) {
                Text("Events:")
                events.forEach { Text(it) }
            }

            LaunchedEffect(runTrigger.intValue) {
                if (runTrigger.intValue == 0) return@LaunchedEffect
                val dispatcher = this.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
                val policy = resilient(ResilientScope(dispatcher)) {
                    timeout { timeout = 2.seconds }
                    retry {
                        maxAttempts = 3
                        backoffStrategy = ExponentialBackoff(initialDelay = 100.milliseconds)
                    }
                    circuitBreaker { failureThreshold = 3 }
                }
                coroutineScope {
                    val collector = launch {
                        policy.events.collect { ev ->
                            events.add(ev.toString())
                            if (events.size > 6) events.removeAt(0)
                        }
                    }
                    try {
                        val value = policy.execute {
                            // Simulate a flaky suspend call
                            delay(100)
                            if (System.currentTimeMillis() % 2L == 0L) throw IllegalStateException("Simulated failure")
                            "OK"
                        }
                        result.value = value
                    } catch (t: Throwable) {
                        error.value = t.message ?: t::class.simpleName
                    } finally {
                        collector.cancel()
                    }
                }
            }
        }
    }
}
