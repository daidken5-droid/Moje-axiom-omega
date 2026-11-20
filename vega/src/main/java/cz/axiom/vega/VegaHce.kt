package cz.axiom.vega

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import cz.axiom.vega.core.CardApdu

/**
 * Telefon jako virtuální karta VEGA (Host Card Emulation). Odpovídá JEN na náš AID a jen na SELECT/READ podle
 * [CardApdu]. Veškerou logiku dělá čistý CardApdu.process – žádný model, žádná síť, žádné UID telefonu.
 * Když není aktivní karta (HceState.payload == null), vrací 6A82.
 */
class VegaHce : HostApduService() {
    override fun processCommandApdu(apdu: ByteArray?, extras: Bundle?): ByteArray =
        CardApdu.process(apdu, HceState.payload(applicationContext))

    override fun onDeactivated(reason: Int) { /* spojení ukončeno/ztraceno – nic nedržíme otevřené */ }
}
