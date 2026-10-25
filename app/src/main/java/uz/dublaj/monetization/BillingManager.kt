package uz.dublaj.monetization

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import uz.dublaj.data.ConfigStore

/**
 * Google Play Billing — bir martalik Pro (`pro_unlock`) va ixtiyoriy obuna (`pro_monthly`).
 *
 * Play Console da product yarating (ID lar pastdagi konstantalar bilan bir xil bo'lsin).
 * Test: internal testing track + license tester email.
 */
object BillingManager : PurchasesUpdatedListener {

    const val PRODUCT_PRO_UNLOCK = "pro_unlock"
    const val PRODUCT_PRO_MONTHLY = "pro_monthly"

    private var client: BillingClient? = null
    private var appContext: Context? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _productDetails = MutableStateFlow<List<ProductDetails>>(emptyList())
    val productDetails: StateFlow<List<ProductDetails>> = _productDetails.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val proListeners = mutableListOf<(Boolean) -> Unit>()

    fun addProListener(listener: (Boolean) -> Unit) {
        proListeners.add(listener)
    }

    fun removeProListener(listener: (Boolean) -> Unit) {
        proListeners.remove(listener)
    }

    fun isPro(context: Context): Boolean = ConfigStore.load(context).isPro

    fun setProLocal(context: Context, pro: Boolean) {
        val cfg = ConfigStore.load(context).copy(isPro = pro)
        ConfigStore.save(context, cfg)
        Timber.i("Pro flag: $pro")
        proListeners.toList().forEach { it(pro) }
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        if (client != null) {
            refreshPurchases(context)
            return
        }
        val bc = BillingClient.newBuilder(context.applicationContext)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .build()
        client = bc
        bc.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    _connected.value = true
                    _lastError.value = null
                    Timber.i("Billing ulandi")
                    queryProducts()
                    refreshPurchases(context.applicationContext)
                } else {
                    _connected.value = false
                    _lastError.value = "Billing: ${result.debugMessage} (${result.responseCode})"
                    Timber.w(_lastError.value)
                }
            }
            override fun onBillingServiceDisconnected() {
                _connected.value = false
                Timber.w("Billing uzildi — qayta uriniladi")
            }
        })
    }

    private fun queryProducts() {
        val bc = client ?: return
        val inapp = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_PRO_UNLOCK)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        bc.queryProductDetailsAsync(inapp) { result, detailsResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val list = detailsResult.productDetailsList.toMutableList()
                // Obuna ixtiyoriy — yo'q bo'lsa xato bermaymiz
                val sub = QueryProductDetailsParams.newBuilder()
                    .setProductList(
                        listOf(
                            QueryProductDetailsParams.Product.newBuilder()
                                .setProductId(PRODUCT_PRO_MONTHLY)
                                .setProductType(BillingClient.ProductType.SUBS)
                                .build()
                        )
                    )
                    .build()
                bc.queryProductDetailsAsync(sub) { r2, d2 ->
                    if (r2.responseCode == BillingClient.BillingResponseCode.OK) {
                        list.addAll(d2.productDetailsList)
                    }
                    _productDetails.value = list
                    Timber.i("ProductDetails: ${list.map { it.productId }}")
                }
            } else {
                Timber.w("ProductDetails xato: ${result.debugMessage}")
            }
        }
    }

    fun refreshPurchases(context: Context) {
        val bc = client
        if (bc == null || !bc.isReady) {
            Timber.d("Billing tayyor emas — refresh o'tkazildi")
            return
        }
        var hasPro = false
        val checkDone = java.util.concurrent.atomic.AtomicInteger(0)
        fun finish() {
            if (checkDone.incrementAndGet() < 2) return
            // Faqat haqiqiy xarid topilsa Pro; yo'q bo'lsa o'chirmaymiz (test local flag saqlansin)
            if (hasPro) {
                setProLocal(context.applicationContext, true)
            }
        }
        bc.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                for (p in purchases) {
                    if (p.products.contains(PRODUCT_PRO_UNLOCK) && p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        hasPro = true
                        acknowledgeIfNeeded(p)
                    }
                }
            }
            finish()
        }
        bc.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                for (p in purchases) {
                    if (p.products.contains(PRODUCT_PRO_MONTHLY) && p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        hasPro = true
                        acknowledgeIfNeeded(p)
                    }
                }
            }
            finish()
        }
    }

    private fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val bc = client ?: return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        bc.acknowledgePurchase(params) { result ->
            Timber.i("Acknowledge: ${result.responseCode} ${result.debugMessage}")
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        val ctx = appContext ?: return
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases.isNullOrEmpty()) return
                var pro = false
                for (p in purchases) {
                    if (p.purchaseState != Purchase.PurchaseState.PURCHASED) continue
                    if (p.products.contains(PRODUCT_PRO_UNLOCK) || p.products.contains(PRODUCT_PRO_MONTHLY)) {
                        pro = true
                        acknowledgeIfNeeded(p)
                    }
                }
                if (pro) {
                    setProLocal(ctx, true)
                    _lastError.value = null
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Timber.i("Xarid bekor qilindi")
            }
            else -> {
                _lastError.value = "Xarid xato: ${result.debugMessage} (${result.responseCode})"
                Timber.w(_lastError.value)
            }
        }
    }

    /**
     * Xarid oynasini ochadi.
     * Product topilmasa yoki Billing ulanmagan bo'lsa — false qaytaradi (UI test unlock qilishi mumkin).
     */
    fun launchPurchase(activity: Activity, productId: String = PRODUCT_PRO_UNLOCK): Boolean {
        val bc = client
        if (bc == null || !bc.isReady) {
            _lastError.value = "Play Billing ulanmagan (Play Store kerak)"
            Timber.w(_lastError.value)
            return false
        }
        val details = _productDetails.value.find { it.productId == productId }
            ?: _productDetails.value.firstOrNull()
        if (details == null) {
            _lastError.value = "Mahsulot topilmadi ($productId). Play Console da yarating."
            Timber.w(_lastError.value)
            return false
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .apply {
                // Obuna bo'lsa base plan
                details.subscriptionOfferDetails?.firstOrNull()?.let { offer ->
                    setOfferToken(offer.offerToken)
                }
            }
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        val result = bc.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _lastError.value = "Flow: ${result.debugMessage}"
            Timber.w(_lastError.value)
            return false
        }
        return true
    }

    /** UI uchun narx matni (masalan "14 000 so'm"). */
    fun priceText(productId: String = PRODUCT_PRO_UNLOCK): String? {
        val d = _productDetails.value.find { it.productId == productId } ?: return null
        d.oneTimePurchaseOfferDetails?.formattedPrice?.let { return it }
        d.subscriptionOfferDetails?.firstOrNull()
            ?.pricingPhases?.pricingPhaseList?.firstOrNull()
            ?.formattedPrice
            ?.let { return it }
        return null
    }
}
