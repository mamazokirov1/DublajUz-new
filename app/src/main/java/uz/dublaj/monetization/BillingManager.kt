package uz.dublaj.monetization

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
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
import uz.dublaj.BuildConfig
import uz.dublaj.data.ConfigStore

/**
 * Google Play Billing 8 — bir martalik Pro (`pro_unlock`) va ixtiyoriy obuna (`pro_monthly`).
 *
 * Play Console da product yarating (ID lar pastdagi konstantalar bilan bir xil bo'lsin).
 * Test: internal testing track + license tester email.
 *
 * Release'da Pro FAQAT haqiqiy xariddan keyin ochiladi va xarid qaytarilsa (refund) yopiladi.
 * Debug (test APK) da "test Pro" qo'lda yoqilishi mumkin (BuildConfig.ALLOW_TEST_UNLOCK).
 */
object BillingManager : PurchasesUpdatedListener {

    const val PRODUCT_PRO_UNLOCK = "pro_unlock"
    const val PRODUCT_PRO_MONTHLY = "pro_monthly"

    private var client: BillingClient? = null
    private var appContext: Context? = null
    private var connecting = false
    private var retryCount = 0
    private val handler = Handler(Looper.getMainLooper())

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
        if (client == null) {
            client = BillingClient.newBuilder(context.applicationContext)
                .setListener(this)
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
                )
                .build()
        }
        val bc = client ?: return
        if (bc.isReady) {
            refreshPurchases(context)
        } else {
            connect()
        }
    }

    /** Ulanish (yoki uzilgandan keyin qayta ulanish) — eksponensial kechikish bilan. */
    private fun connect() {
        val bc = client ?: return
        if (connecting || bc.isReady) return
        connecting = true
        bc.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    retryCount = 0
                    _connected.value = true
                    _lastError.value = null
                    Timber.i("Billing ulandi")
                    queryProducts()
                    appContext?.let { refreshPurchases(it) }
                } else {
                    _connected.value = false
                    _lastError.value = "Billing: ${result.debugMessage} (${result.responseCode})"
                    Timber.w(_lastError.value)
                    scheduleReconnect()
                }
            }

            override fun onBillingServiceDisconnected() {
                connecting = false
                _connected.value = false
                Timber.w("Billing uzildi — qayta uriniladi")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (retryCount >= 6) return
        val delayMs = (2_000L shl retryCount).coerceAtMost(60_000L)
        retryCount++
        handler.postDelayed({ connect() }, delayMs)
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
            val list: MutableList<ProductDetails> = if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                extractDetails(detailsResult).toMutableList()
            } else {
                Timber.w("ProductDetails xato: ${result.debugMessage}")
                mutableListOf()
            }
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
                    list.addAll(extractDetails(d2))
                }
                _productDetails.value = list
                Timber.i("ProductDetails: ${list.map { it.productId }}")
            }
        }
    }

    /**
     * Billing versiyalarida queryProductDetailsAsync javobi turlicha (List yoki natija obyekti).
     * Ikkalasini ham qabul qiladi, shuning uchun kutubxona versiyasiga bog'liq emas.
     */
    private fun extractDetails(r: Any?): List<ProductDetails> {
        if (r == null) return emptyList()
        if (r is List<*>) return r.filterIsInstance<ProductDetails>()
        return try {
            val m = r.javaClass.getMethod("getProductDetailsList")
            (m.invoke(r) as? List<*>)?.filterIsInstance<ProductDetails>() ?: emptyList()
        } catch (e: Exception) {
            Timber.w(e, "ProductDetails javobi o'qilmadi")
            emptyList()
        }
    }

    fun refreshPurchases(context: Context) {
        val bc = client
        if (bc == null || !bc.isReady) {
            Timber.d("Billing tayyor emas — ulanish urinib ko'riladi")
            connect()
            return
        }
        val appCtx = context.applicationContext
        var hasPro = false
        var allOk = true
        val checkDone = java.util.concurrent.atomic.AtomicInteger(0)
        fun finish() {
            if (checkDone.incrementAndGet() < 2) return
            if (hasPro) {
                if (!isPro(appCtx)) setProLocal(appCtx, true)
            } else if (allOk && !BuildConfig.ALLOW_TEST_UNLOCK && isPro(appCtx)) {
                // Release: xarid topilmadi (qaytarilgan/bekor qilingan) — Pro yopiladi.
                // Faqat ikkala so'rov ham muvaffaqiyatli bo'lsa (tarmoq xatosida o'chirmaymiz).
                setProLocal(appCtx, false)
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
            } else {
                allOk = false
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
            } else {
                // Obuna mahsuloti yaratilmagan bo'lsa SUBS so'rovi xato qaytarishi mumkin —
                // bunda "xarid yo'q" deb hisoblamaymiz.
                allOk = false
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
                var pending = false
                for (p in purchases) {
                    val isOurs = p.products.contains(PRODUCT_PRO_UNLOCK) || p.products.contains(PRODUCT_PRO_MONTHLY)
                    if (!isOurs) continue
                    when (p.purchaseState) {
                        Purchase.PurchaseState.PURCHASED -> {
                            pro = true
                            acknowledgeIfNeeded(p)
                        }
                        Purchase.PurchaseState.PENDING -> pending = true
                        else -> {}
                    }
                }
                if (pro) {
                    setProLocal(ctx, true)
                    _lastError.value = null
                } else if (pending) {
                    _lastError.value = "To'lov kutilmoqda — tasdiqlangach Pro avtomatik ochiladi"
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Timber.i("Xarid bekor qilindi")
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                // Allaqachon sotib olingan — holatni qayta tiklaymiz
                refreshPurchases(ctx)
            }
            else -> {
                _lastError.value = "Xarid xato: ${result.debugMessage} (${result.responseCode})"
                Timber.w(_lastError.value)
            }
        }
    }

    /**
     * Xarid oynasini ochadi.
     * Product topilmasa yoki Billing ulanmagan bo'lsa — false qaytaradi; sabab [lastError] da.
     */
    fun launchPurchase(activity: Activity, productId: String = PRODUCT_PRO_UNLOCK): Boolean {
        val bc = client
        if (bc == null || !bc.isReady) {
            _lastError.value = "Google Play ulanmagan. Ilovani Play Market orqali o'rnating va internetni tekshiring."
            Timber.w(_lastError.value)
            connect()
            return false
        }
        val details = _productDetails.value.find { it.productId == productId }
        if (details == null) {
            _lastError.value = "Mahsulot topilmadi ($productId). Play Console da yaratilganini tekshiring."
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
            _lastError.value = "Xarid oynasi ochilmadi: ${result.debugMessage}"
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
