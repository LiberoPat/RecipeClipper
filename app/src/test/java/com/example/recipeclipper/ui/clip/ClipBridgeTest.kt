package com.example.recipeclipper.ui.clip

import com.example.recipeclipper.data.model.ClipField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The clip view hears its page's main frame only (#235): an ad's or another site's iframe can't
 * post a selection, a tag or a photo into the clip. The device's ClipScreenTest posts from a real
 * iframe; iOS's ClipPageEventTests are the same rule.
 */
class ClipBridgeTest {

    private val selection = """{"type":"selection","text":"2 cups flour"}"""

    @Test fun `the main frame's messages are read`() {
        assertEquals(ClipPageEvent.Selection("2 cups flour"), ClipBridge.fromListener(selection, isMainFrame = true))
        assertEquals(
            ClipPageEvent.TagTapped(ClipField.STEPS),
            ClipBridge.fromListener("""{"type":"tag","field":"STEPS"}""", isMainFrame = true)
        )
        assertEquals(ClipPageEvent.NoImage, ClipBridge.fromListener("""{"type":"noImage"}""", isMainFrame = true))
    }

    @Test fun `a message from any other frame is dropped`() {
        assertNull(ClipBridge.fromListener(selection, isMainFrame = false))
        assertNull(ClipBridge.fromListener("""{"type":"image","src":"https://ads.example/a.jpg"}""", isMainFrame = false))
        assertNull(ClipBridge.fromListener("""{"type":"tag","field":"NAME"}""", isMainFrame = false))
        assertNull(ClipBridge.fromListener("""{"type":"noImage"}""", isMainFrame = false))
    }

    @Test fun `a message that isn't one of the page's is dropped`() {
        assertNull(ClipBridge.fromListener(null, isMainFrame = true))
        assertNull(ClipBridge.fromListener("not json", isMainFrame = true))
        assertNull(ClipBridge.fromListener("""{"type":"other"}""", isMainFrame = true))
        assertNull(ClipBridge.fromListener("""{"type":"tag","field":"OVEN"}""", isMainFrame = true))
    }

    @Test fun `a tapped image's address goes to the ViewModel as it is, to be judged there`() {
        assertEquals(
            ClipPageEvent.ImageTapped("file:///data/x.db"),
            ClipBridge.fromListener("""{"type":"image","src":"file:///data/x.db"}""", isMainFrame = true)
        )
        assertEquals(ClipPageEvent.ImageTapped(""), ClipBridge.fromListener("""{"type":"image"}""", isMainFrame = true))
    }

    // An old WebView with no message listener: the interface every frame sees, and a token only
    // the main frame was handed.

    @Test fun `through the old interface, only a message carrying the main frame's token is read`() {
        val token = "4f1c2a9e-token"
        assertEquals(
            ClipPageEvent.Selection("2 cups flour"),
            ClipBridge.fromInterface("""{"type":"selection","text":"2 cups flour","token":"$token"}""", token)
        )
        assertNull(ClipBridge.fromInterface(selection, token))
        assertNull(ClipBridge.fromInterface("""{"type":"selection","text":"x","token":"guess"}""", token))
        assertNull(ClipBridge.fromInterface("""{"type":"selection","text":"x","token":""}""", token))
        assertNull(ClipBridge.fromInterface("not json", token))
    }

    @Test fun `the token is handed to the main frame as a quoted string`() {
        assertEquals("window.RCBridgeToken = \"a'b\\\"c\";", ClipBridge.tokenScript("a'b\"c"))
    }
}
