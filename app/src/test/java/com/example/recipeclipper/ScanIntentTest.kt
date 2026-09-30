package com.example.recipeclipper

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Images shared into the app (#226): the manifest takes `ACTION_SEND` and `ACTION_SEND_MULTIPLE`
 * of any image type, and [ScanIntent] turns them into the scan's pages, in order, which
 * MainActivity queues as the review's route.
 */
@RunWith(AndroidJUnit4::class)
class ScanIntentTest {

    private val front = Uri.parse("content://com.google.android.apps.photos.contentprovider/0/1/front.jpg")
    private val back = Uri.parse("content://com.google.android.apps.photos.contentprovider/0/1/back.jpg")

    private fun resolvesToMainActivity(intent: Intent): Boolean {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return context.packageManager.queryIntentActivities(intent.setPackage(context.packageName), 0)
            .any { it.activityInfo.name == MainActivity::class.java.name }
    }

    @Test
    fun oneSharedImageIsOnePage() {
        val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, front)

        assertTrue(resolvesToMainActivity(intent))
        assertEquals(listOf(front.toString()), ScanIntent.pages(intent))
    }

    @Test
    fun severalSharedImagesArePagesInTheOrderSent() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(front, back))

        assertTrue(resolvesToMainActivity(intent))
        val pages = ScanIntent.pages(intent)
        assertEquals(listOf(front.toString(), back.toString()), pages)
        assertEquals(
            "edit/scan?pages=" + Uri.encode("$front\n$back"),
            Routes.scan(pages!!)
        )
    }

    @Test
    fun atMostSixPagesAreTaken() {
        val uris = (1..9).map { Uri.parse("content://photos/$it") }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))

        assertEquals(uris.take(6).map(Uri::toString), ScanIntent.pages(intent))
    }

    @Test
    fun aSharedLinkOrFileIsNotAScan() {
        assertNull(ScanIntent.pages(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://x.com")))
        assertNull(
            ScanIntent.pages(
                Intent(Intent.ACTION_SEND).setType("application/vnd.recipeclipper+json").putExtra(Intent.EXTRA_STREAM, front)
            )
        )
        assertNull(ScanIntent.pages(Intent(Intent.ACTION_VIEW).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, front)))
        assertNull(ScanIntent.pages(Intent(Intent.ACTION_SEND).setType("image/jpeg")))
        assertNull(ScanIntent.pages(null))
    }
}
