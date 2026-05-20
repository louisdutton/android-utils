package digital.dutton.essentials.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class AssistantVoiceInteractionService : VoiceInteractionService()

class AssistantVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return AssistantVoiceInteractionSession(this)
    }
}
