package cz.axiom.omega.assist

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import cz.axiom.omega.MainActivity

/**
 * AVIOR JAKO VÝCHOZÍ DIGITÁLNÍ ASISTENT (místo Gemini).
 *
 * Nastavení → Aplikace → Výchozí aplikace → Digitální asistent → AVIOR. Pak dlouhý stisk vypínače
 * (nebo gesto z rohu) otevře AVIOR rovnou s hlasovým dotazem, který se po dořečení sám odešle.
 * Android vyžaduje trojici: VoiceInteractionService + SessionService + RecognitionService.
 */
class AviorAssistService : VoiceInteractionService()

class AviorSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = object : VoiceInteractionSession(this) {
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            startAssistantActivity(Intent(context, MainActivity::class.java).setAction(ACTION_ASSIST_VOICE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            hide()
        }
    }

    companion object { const val ACTION_ASSIST_VOICE = "cz.axiom.omega.ASSIST_VOICE" }
}

/** Povinná součást asistenta. AVIOR používá systémové rozpoznávání řeči, vlastní neposkytuje. */
class AviorRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) { runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) } }
    override fun onCancel(listener: Callback?) = Unit
    override fun onStopListening(listener: Callback?) = Unit
}

/** Dlaždice v rychlém nastavení: jedno klepnutí → hlasový dotaz AVIORu (i bez odemknutí aplikace). */
class AviorTileService : TileService() {
    // API < 34: jen větev s Intentem (na 34+ by vyhodila UnsupportedOperationException) – lint hlásí falešně
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val i = Intent(this, MainActivity::class.java).setAction(AviorSessionService.ACTION_ASSIST_VOICE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 11, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }
}
