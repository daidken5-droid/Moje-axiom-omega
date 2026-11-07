package cz.axiom.omega.data

import cz.axiom.omega.core.agent.Variant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Regrese z telefonu (1016): běh bez jistoty uložil „conf“: null → každý další dotaz spadl na getInt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RandyStoreTest {
    @Test fun runWithoutConfidenceDoesNotBreakNextQuestion() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("randy", 0).edit().clear().commit()
        val st = RandyStore(ctx)
        st.record(Variant.AUTO, 1200, null, false)      // odpověď bez jistoty (web, abstence…)
        val s = st.stats()                               // dřív: JSONException „Value null at conf“
        assertEquals(1, s.getValue(Variant.AUTO).runs)
        assertNull(s.getValue(Variant.AUTO).avgConfidence)
        st.record(Variant.AUTO, 800, 80, false)          // další běh s jistotou funguje dál
        assertEquals(2, st.stats().getValue(Variant.AUTO).runs)
        // poškozený záznam jedné varianty se přeskočí, nic nespadne
        ctx.getSharedPreferences("randy", 0).edit().putString("stats", """{"AUTO": 5, "FLASH": {"runs": 1, "conf": null}}""").commit()
        assertEquals(1, RandyStore(ctx).stats().getValue(Variant.FLASH).runs)
    }
}
