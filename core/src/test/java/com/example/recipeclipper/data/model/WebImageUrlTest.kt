package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A photo's address from a page is a web image or nothing (#235). iOS: WebImageUrlTests. */
class WebImageUrlTest {

    private fun of(address: String?) = WebImageUrl.of(address)

    @Test fun `an https image is kept as it is`() {
        val image = "https://img.example/a/cookies.jpg?w=1200&h=800#top"
        assertEquals(image, of(image))
        assertEquals("https://img.example:8443/a.jpg", of("https://img.example:8443/a.jpg"))
        assertEquals("https://cdn.example?id=4", of("https://cdn.example?id=4"))
    }

    @Test fun `http is upgraded to https, as a link is`() {
        assertEquals("https://img.example/a.jpg", of("http://img.example/a.jpg"))
        assertEquals("https://Img.Example/A.jpg", of("HTTP://Img.Example/A.jpg"))
        assertEquals("https://img.example/a.jpg", of("HTTPS://img.example/a.jpg"))
    }

    @Test fun `spaces around the address are dropped`() {
        assertEquals("https://img.example/a.jpg", of("  https://img.example/a.jpg\n"))
    }

    @Test fun `an address on the device is no image`() {
        assertNull(of("file:///data/data/com.example.recipeclipper/databases/recipe_clipper.db"))
        assertNull(of("content://media/external/images/media/12"))
        assertNull(of("android.resource://com.example.recipeclipper/drawable/x"))
        assertNull(of("/data/user/0/com.example.recipeclipper/files/photo.jpg"))
    }

    @Test fun `an address that isn't a web link is no image`() {
        assertNull(of("data:image/gif;base64,R0lGODlhAQABAAAAACw="))
        assertNull(of("javascript:alert('https://img.example/a.jpg')"))
        assertNull(of("blob:https://img.example/1234"))
        assertNull(of("ftp://img.example/a.jpg"))
        assertNull(of("intent://img.example/#Intent;end"))
    }

    @Test fun `a relative or blank address is no image`() {
        assertNull(of("/img/cookies.jpg"))
        assertNull(of("img/cookies.jpg"))
        assertNull(of("//cdn.example/cookies.jpg"))
        assertNull(of(""))
        assertNull(of("   "))
        assertNull(of(null))
    }

    @Test fun `a web address with no host is no image`() {
        assertNull(of("https://"))
        assertNull(of("https:///img/a.jpg"))
        assertNull(of("http://?x=1"))
        assertNull(of("https://user@:443/a.jpg"))
        assertNull(of("https:/img.example/a.jpg"))
    }

    @Test fun `a user name before the host is not the host`() {
        assertEquals("https://user:pw@img.example/a.jpg", of("https://user:pw@img.example/a.jpg"))
    }
}
