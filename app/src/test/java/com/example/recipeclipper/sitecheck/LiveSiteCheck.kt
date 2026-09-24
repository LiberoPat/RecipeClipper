package com.example.recipeclipper.sitecheck

import com.example.recipeclipper.data.Connectivity
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.remote.BlogRecipeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.NetworkInterface
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The weekly check that real recipe sites still parse (#32): the real [BlogRecipeSource] over
 * every URL in `site-check-urls.txt`, reported through [SiteReport].
 *
 * Hits the network, so it is excluded from the normal unit-test run and runs only with
 * `./gradlew testDebugUnitTest -PsiteCheck` (see app/build.gradle.kts), which also sets
 * `siteCheck.out`. It never fails because a site failed: sites block on and off, so the result
 * is a report, not a verdict. It fails only when the harness itself breaks (no URL list,
 * nowhere to write, an exception out of the source).
 */
class LiveSiteCheck {

    /** On the JVM there's no ConnectivityManager: online means an interface other than
     *  loopback is up. Only consulted after an IOException, to tell Offline apart. */
    private object JvmConnectivity : Connectivity {
        override fun isOnline(): Boolean =
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { it.isUp && !it.isLoopback }

        override val online: Flow<Boolean> get() = flowOf(isOnline())
    }

    @Test fun checkSites(): Unit = runBlocking {
        val outPath = System.getProperty("siteCheck.out")
        assumeTrue("Runs only with -PsiteCheck", outPath != null)

        val list = javaClass.getResource("/site-check-urls.txt")?.readText()
        val urls = SiteReport.parseUrlList(checkNotNull(list) { "site-check-urls.txt is missing" })
        assertTrue("site-check-urls.txt lists no URLs", urls.isNotEmpty())

        val source = BlogRecipeSource(JvmConnectivity)
        val outcomes = urls.map { url ->
            val start = System.nanoTime()
            val first = source.fetch(url)
            val firstError = (first as? ParseResult.Error)?.error
            // The repository's rule: one retry after 2 s, for a block or a non-timeout
            // failure only. What the user would end up seeing is what's reported.
            val (result, firstCause) = if (firstError?.shouldAutoRetry == true) {
                delay(2_000)
                source.fetch(url) to firstError
            } else {
                first to null
            }
            val millis = (System.nanoTime() - start) / 1_000_000
            SiteReport.Outcome(url, result, firstCause, millis).also {
                println("${SiteReport.site(url)}: " + when (result) {
                    is ParseResult.Success -> "parsed, ${result.recipe.ingredients.size} ingredients, " +
                        "${result.recipe.instructions.size} steps"
                    is ParseResult.Error -> SiteReport.describe(result.error)
                })
            }
        }

        val runAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val out = File(outPath!!).apply { mkdirs() }
        File(out, "results.md").writeText(SiteReport.markdown(outcomes, runAt))
        File(out, "results.json").writeText(SiteReport.json(outcomes, runAt))
        println(SiteReport.markdown(outcomes, runAt))
        if (outcomes.all { (it.result as? ParseResult.Error)?.error == ParseError.Offline }) {
            error("Every fetch was Offline: the runner has no network, so this run says nothing")
        }
    }
}
