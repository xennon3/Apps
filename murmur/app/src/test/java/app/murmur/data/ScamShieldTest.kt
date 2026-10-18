package app.murmur.data

import app.murmur.data.ScamShield.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScamShieldTest {
    private fun risk(body: String, sender: String = "+15559998888", contact: Boolean = false) =
        ScamShield.analyze(body, sender, contact).risk

    @Test fun catchesClassicSmishing() {
        assertEquals(Risk.DANGER, risk("USPS: Your package could not be delivered due to an incomplete address. Update here: https://usps-redelivery.top/track"))
        assertEquals(Risk.DANGER, risk("FINAL NOTICE: You have an unpaid toll balance of \$6.99. Pay within 24 hours to avoid a late fee: ezpass-pay.xyz", sender = "scam@toll-notice.com"))
        assertEquals(Risk.DANGER, risk("Your Chase account has been suspended due to unusual activity. Verify your identity: http://192.168.4.20/login"))
        assertEquals(Risk.DANGER, risk("Amazon: a refund of \$89 is waiting. Please reply Y, then exit the text and reopen it to activate the link amzn-refunds.click/r"))
    }

    @Test fun shortLinksFromStrangersAreCaution() {
        val v = ScamShield.analyze("Your order is on hold, confirm your details bit.ly/3xYzAb", "+15551112222", false)
        assertTrue(v.flagged)
    }

    @Test fun leavesNormalTextsAlone() {
        assertEquals(Risk.NONE, risk("check out this place for dinner https://www.yelp.com/biz/joes-pizza", contact = true))
        assertEquals(Risk.NONE, risk("Your USPS package was delivered. Track at https://tools.usps.com/go/TrackConfirmAction?tLabels=9400"))
        assertEquals(Risk.NONE, risk("meet at 7.30 by the fountain, e.g. near the cups store"))
        assertEquals(Risk.NONE, risk("lol did you see groups.google.com/whatever", contact = true))
        assertEquals(Risk.NONE, risk("Your Acme verification code is 482913"))
        assertEquals(Risk.NONE, risk("first thing tomorrow I'll send the irs forms", contact = true))
    }

    @Test fun findsLinks() {
        assertEquals(listOf("https://a.com/x", "bit.ly/abc"), ScamShield.links("go https://a.com/x, or bit.ly/abc."))
        assertEquals(emptyList<String>(), ScamShield.links("costs 3.50 and email me at a@b.com"))
    }
}
