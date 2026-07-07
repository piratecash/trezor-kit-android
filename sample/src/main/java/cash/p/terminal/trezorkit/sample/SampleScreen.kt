package cash.p.terminal.trezorkit.sample

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Pure UI for the manual USB harness - state down, events up. */
@Composable
internal fun SampleScreen(status: String, isLoading: Boolean, onConnect: () -> Unit) {
    Column(modifier = Modifier.padding(24.dp)) {
        Button(onClick = onConnect, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) {
            Text("Connect & Get Features")
        }
        Text(text = status, modifier = Modifier.padding(top = 16.dp))
    }
}

@Preview
@Composable
private fun SampleScreenPreview() {
    MaterialTheme {
        SampleScreen(status = "deviceId: mock-id", isLoading = false, onConnect = {})
    }
}
