package digital.dutton.agent

import android.content.Intent
import android.speech.RecognitionService

class AgentRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, callback: Callback?) {
        // Minimal implementation
    }

    override fun onCancel(callback: Callback?) {
        // Minimal implementation
    }

    override fun onStopListening(callback: Callback?) {
        // Minimal implementation
    }
}
