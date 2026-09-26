package com.example.recipeclipper.data

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.core.content.edit
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The real [Entitlements] on Android: Google Play Billing, product [UNLIMITED_RECIPES_PRODUCT_ID]
 * (a one-time, non-consumable product; create it in Play Console, #22). The last answer is
 * cached in its own prefs file, so a share that cold-starts the app is judged by it rather
 * than by "locked" while Play is still being asked. [start] asks Play at every launch, which
 * also picks up a refund or a purchase made on another device.
 *
 * Play needs the resumed Activity for its purchase sheet; it's tracked here from the
 * Application's lifecycle callbacks, so no ViewModel ever holds one.
 */
@Singleton
class PlayBillingEntitlements @Inject constructor(
    @ApplicationContext context: Context,
    private val log: ErrorLog
) : Entitlements, PurchasesUpdatedListener {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(UnlockState(unlocked = prefs.getBoolean(KEY_UNLOCKED, false)))
    override val state: StateFlow<UnlockState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private var product: ProductDetails? = null
    private var resumed: WeakReference<Activity>? = null
    private var inFlight: CompletableDeferred<PurchaseOutcome>? = null

    /** Called once from the Application: tracks the resumed Activity and asks Play. */
    fun start(app: Application) {
        app.registerActivityLifecycleCallbacks(ResumedActivityTracker { resumed = it?.let(::WeakReference) })
        scope.launch { refresh() }
    }

    override suspend fun purchase(): PurchaseOutcome {
        if (!connect()) return PurchaseOutcome.FAILED
        val details = product ?: loadProduct() ?: return PurchaseOutcome.FAILED
        val activity = resumed?.get() ?: return PurchaseOutcome.FAILED
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build())
            )
            .build()
        val answer = CompletableDeferred<PurchaseOutcome>()
        inFlight = answer
        val launched = withContext(Dispatchers.Main) { client.launchBillingFlow(activity, params) }
        val outcome = when (launched.responseCode) {
            BillingResponseCode.OK -> answer.await()
            BillingResponseCode.ITEM_ALREADY_OWNED -> restore()
            else -> {
                log.error("launchBillingFlow", BillingError(launched))
                PurchaseOutcome.FAILED
            }
        }
        inFlight = null
        return outcome
    }

    override suspend fun restore(): PurchaseOutcome {
        if (!refresh()) return PurchaseOutcome.FAILED
        val now = _state.value
        return when {
            now.unlocked -> PurchaseOutcome.UNLOCKED
            now.pending -> PurchaseOutcome.PENDING
            else -> PurchaseOutcome.NOTHING_TO_RESTORE
        }
    }

    /** Play's answer to a purchase sheet, or a pending purchase that completed later. */
    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        val list = purchases.orEmpty().toList()
        scope.launch {
            val outcome = when (result.responseCode) {
                BillingResponseCode.OK -> apply(list)
                BillingResponseCode.USER_CANCELED -> PurchaseOutcome.CANCELLED
                BillingResponseCode.ITEM_ALREADY_OWNED -> restore()
                else -> PurchaseOutcome.FAILED
            }
            inFlight?.complete(outcome)
        }
    }

    /** Asks Play for this account's purchases and the price. False if Play didn't answer. */
    private suspend fun refresh(): Boolean {
        if (!connect()) return false
        if (product == null) loadProduct()
        val params = QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()
        val result = client.queryPurchasesAsync(params)
        if (result.billingResult.responseCode != BillingResponseCode.OK) return false
        apply(result.purchasesList)
        return true
    }

    /** Takes [purchases] as the whole truth: acknowledges a new one, caches the answer. */
    private suspend fun apply(purchases: List<Purchase>): PurchaseOutcome {
        val ours = purchases.filter { UNLIMITED_RECIPES_PRODUCT_ID in it.products }
        val bought = ours.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val pending = bought == null && ours.any { it.purchaseState == Purchase.PurchaseState.PENDING }
        if (bought != null && !bought.isAcknowledged) {
            // Unacknowledged purchases are refunded by Play after three days.
            val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(bought.purchaseToken).build()
            val ack = client.acknowledgePurchase(params)
            if (ack.responseCode != BillingResponseCode.OK) log.error("acknowledge", BillingError(ack))
        }
        prefs.edit { putBoolean(KEY_UNLOCKED, bought != null) }
        _state.update { it.copy(unlocked = bought != null, pending = pending) }
        return when {
            bought != null -> PurchaseOutcome.UNLOCKED
            pending -> PurchaseOutcome.PENDING
            else -> PurchaseOutcome.FAILED
        }
    }

    private suspend fun loadProduct(): ProductDetails? {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(UNLIMITED_RECIPES_PRODUCT_ID)
                        .setProductType(ProductType.INAPP)
                        .build()
                )
            )
            .build()
        val result = client.queryProductDetails(params)
        val details = result.productDetailsList?.firstOrNull() ?: return null
        product = details
        _state.update { it.copy(price = details.oneTimePurchaseOfferDetails?.formattedPrice) }
        return details
    }

    private suspend fun connect(): Boolean {
        if (client.isReady) return true
        return suspendCancellableCoroutine { done ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (done.isActive) done.resume(result.responseCode == BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (done.isActive) done.resume(false)
                }
            })
        }
    }

    companion object {
        /** Its own prefs file: never `unit_preferences`, and not in the backup include list. */
        const val FILE = "entitlements"
        const val KEY_UNLOCKED = "unlocked"
    }
}

/** A refused Billing call, for the log. */
private class BillingError(result: BillingResult) :
    Exception("Billing ${result.responseCode}: ${result.debugMessage}")

/** Reports the resumed Activity to [onChange], and null once it is destroyed. */
private class ResumedActivityTracker(
    private val onChange: (Activity?) -> Unit
) : Application.ActivityLifecycleCallbacks {
    private var current: Activity? = null

    override fun onActivityResumed(activity: Activity) {
        current = activity
        onChange(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (current === activity) {
            current = null
            onChange(null)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
