package com.example.recipeclipper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The one pure piece of the WebView renderer: undoing `evaluateJavascript`'s JSON encoding.
 *  The rest needs a real WebView, so it is checked on a device. */
class WebViewRenderedPageSourceTest {

    @Test fun `a JSON string literal decodes to the HTML it holds`() {
        assertEquals(
            "<html><body class=\"x\">Café\n</body></html>",
            WebViewRenderedPageSource.decodeJsString("\"<html><body class=\\\"x\\\">Caf\\u00e9\\n</body></html>\"")
        )
    }

    @Test fun `null, a non-string or garbage decodes to null`() {
        assertNull(WebViewRenderedPageSource.decodeJsString(null))
        assertNull(WebViewRenderedPageSource.decodeJsString("null"))
        assertNull(WebViewRenderedPageSource.decodeJsString("42"))
        assertNull(WebViewRenderedPageSource.decodeJsString(""))
    }
}
