package digital.dutton.agent

import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AgentTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Agent",
                            style = MaterialTheme.typography.headlineLarge
                        )
                        Button(
                            onClick = { requestAssistantRole() },
                            modifier = Modifier.padding(top = 16.dp)
                        ) {
                            Text("Set as default assistant")
                        }
                    }
                }
            }
        }
    }

    private fun requestAssistantRole() {
        val roleManager = getSystemService(RoleManager::class.java)
        val available = roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
        val held = roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)

        Toast.makeText(this, "Role available: $available, held: $held", Toast.LENGTH_LONG).show()

        if (!available) {
            // Fallback: open assistant settings directly
            try {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
            return
        }

        if (!held) {
            try {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                Toast.makeText(this, "Launching role request...", Toast.LENGTH_SHORT).show()
                startActivityForResult(intent, REQUEST_ASSISTANT_ROLE)
            } catch (e: Exception) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ASSISTANT_ROLE) {
            Toast.makeText(this, "Result: $resultCode", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQUEST_ASSISTANT_ROLE = 1
    }
}

@Composable
fun AgentTheme(content: @Composable () -> Unit) {
    val colorScheme = dynamicDarkColorScheme(LocalContext.current)
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
