# DublajUz (Android)Shoirjon

Offline o'zbekcha video dublyaj: **Whisper (STT) → Gemini (tarjima) → tahrir → VITS (o'zbek TTS) → MP4**.

Matn aniqlash va ovoz sintezi telefonda offline ishlaydi. Tarjima uchun Gemini API kaliti kerak (internet).

## Ikki xil build

| | Test APK (`debug`) | Play (`release` / AAB) |
|---|---|---|
| Qayerdan | GitHub Releases / Actions artifact | Actions artifact `DublajUz-release-aab` |
| Imzo | doimiy ochiq test kaliti (APK ustidan yangilanadi) | **sizning upload key** (Secrets) |
| Reklama | Google test reklamalari | haqiqiy ID (Secrets) yoki test |
| O'zini yangilash (GitHub) | bor | **yo'q** (Play siyosati) |
| "Test Pro" tugmasi | bor | **yo'q** — Pro faqat haqiqiy xariddan |
| Server (Chatterbox) ovozi | bor (tajriba) | **yo'q** |

Batafsil Play qadamlari: **PLAY_CHECKLIST.md**. Maxfiylik siyosati: `PRIVACY.md` (+ `docs/index.html` — GitHub Pages uchun).

## GitHub'da APK yig'ish
1. Loyihani GitHub repo'ga push qiling (`main` yoki `master`).
2. **Actions → Build APK** ishga tushadi (yoki *Run workflow*).
3. Test APK: **Releases → eng yangi `build-N`** → `DublajUz.apk` (yoki run sahifasi → Artifacts → `DublajUz-debug-apk`).
4. Run logida **"Check 16 KB page size"** qadami Play'ning 16 KB talabini tekshiradi.
5. Play uchun: Secrets (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`; ixtiyoriy `ADMOB_APP_ID`, `ADMOB_BANNER_ID`, `ADMOB_INTERSTITIAL_ID`) qo'shilgach, shu workflow **AAB** ham yig'adi.

CI repodagi `gradle-wrapper.jar` ni ishlatmaydi — rasmiy Gradle 8.11.1 o'rnatadi (AGP 8.9.1, compileSdk/targetSdk 36).

## Birinchi ishga tushirish (telefonda)
**Sozlamalar** ekranida:
- **Whisper**: model tanlang (base tavsiya) → *Yuklash* (yoki `ggml-*.bin` ni import).
- **Gemini**: Onlayn tarjima yoqing va Google AI Studio'dan olingan API kalitini kiriting.
- **VITS**: "Ovozni internetdan yuklash" (reliz `models-v1` dan) yoki `vits_uzbek.onnx` va `uz_tts_base.json` ni qo'lda import qiling. Reliz yaratish: `tools/publish_models.sh`.
- **Video tili**: Ru/En dan boshqa tillar uchun tilni aniq tanlang.

So'ng: video tanlang → *Transkript + tarjima* → tahrirlang → *Tasdiqlash* → *Dublyaj qilish*.
Natijani *Ko'rish / Ulashish / Galereyaga saqlash* mumkin.

## Monetizatsiya g'oyalari (Free / Pro)

### Free (reklama bilan)
- Kuniga cheklangan daqiqalar yoki video soni (masalan 2–3 ta qisqa video).
- Banner + interstitial (dublyaj tugagach).
- Google AdMob.

### Pro (bir martalik yoki obuna)
- Cheksiz foydalanish.
- Reklamasiz.
- Yuqori sifatli TTS (Chatterbox server) yoki qo'shimcha ovozlar.
- Batch / navbat, priorit etish.
- Google Play Billing Library (In-app product yoki Subscription).

### Implementatsiya qadamlari
1. AdMob app ID va ad unit ID larni sozlash.
2. Play Billing Library qo'shish, product ID lar (masalan `pro_unlock`, `pro_monthly`).
3. ConfigStore ga `isPro` flag qo'shish, unlock holatini saqlash.
4. Free da limit tekshiruvi + reklama ko'rsatish.
5. Pro unlock ekrani / Settings da "Pro ga o'tish".

## Cheklovlar
- Faqat **arm64-v8a** (Whisper AAR bepul versiyasi).
- Video MP4 uchun mos kodekda bo'lishi kerak (H.264/H.265).
- Tarjima uchun internet + Gemini API kaliti majburiy.
- Kamida ~2–3 GB RAM tavsiya etiladi.

## Versiya
0.4.x (NLLB olib tashlangan, Play Market tayyorlash)
- billing_v8: targetSdk 36, Billing 8, UMP roziligi, o'zini yangilash va test-Pro faqat debug, upload key CI orqali, 16 KB tekshiruvi

## Keyingi qadamlar (Play Market + daromad)

### 1. Privacy Policy
- `PRIVACY.md` shablon tayyor.
- Uni GitHub Pages yoki oddiy saytga joylashtiring.
- Play Console → App content → Privacy policy da URL kiriting.

### 2. Free / Pro + Ads (kod skeleti tayyor)
- `uz.dublaj.monetization.BillingManager` — Play Billing stub
- `uz.dublaj.monetization.AdsManager` — AdMob stub
- `uz.dublaj.data.UsageStore` — kunlik limit (Free: 3 video / ~15 daqiqa)
- `AppConfig.isPro` — Pro flag

**Qilish kerak:**
1. AdMob akkaunt oching → App + Banner/Interstitial unit ID oling.
2. Play Console → Monetize → In-app products / Subscriptions yarating (`pro_unlock` yoki `pro_monthly`).
3. `build.gradle.kts` dagi billing va ads dependency larni comment dan chiqaring.
4. `BillingManager` va `AdsManager` dagi TODO larni haqiqiy kod bilan to'ldiring.
5. Settings yoki alohida ekranda "Pro ga o'tish" tugmasi qo'shing.
6. Dublyaj tugagach `UsageStore.recordDub` + `AdsManager.showInterstitialAfterDub` chaqiring.
7. Pipeline boshlashdan oldin `UsageStore.canDub` tekshiring.

### 3. Play Console checklist
- [ ] Privacy policy URL
- [ ] Data safety formasi (Gemini matn, AdMob, Billing)
- [ ] Content rating
- [ ] Target audience
- [ ] Store listing (icon 512, screenshots, description)
- [ ] Internal testing track ga AAB yuklash
- [ ] App signing (Play App Signing)

### 4. Self-update
`UpdateChecker` GitHub dan APK yuklab o'rnatishga urinadi. Play Store ilovalarida buni o'chirish yoki faqat debug build da qoldirish tavsiya etiladi (Play In-App Updates kutubxonasi bilan almashtirish mumkin).

## AdMob holati (v5+)

Hozir **Google test ID** lari ulangan — debug da reklama ko'rinadi, haqiqiy daromad yo'q.

1. [AdMob](https://admob.google.com) da ilova yarating.
2. App ID ni oling → `AndroidManifest.xml` dagi `APPLICATION_ID` ni almashtiring.
3. Banner va Interstitial unit ID oling → `AdsManager.BANNER_AD_UNIT` va `INTERSTITIAL_AD_UNIT`.
4. Pro foydalanuvchida reklama ko'rsatilmaydi (`isPro == true`).

Interstitial: dublyaj muvaffaqiyatli tugagach (`PipelineState.Success`).
Banner: Home pastki qismi (`Scaffold.bottomBar`).


## Play Billing (Pro xarid)

Dependency: `billing-ktx:7.1.1` yoqilgan.

**Play Console da:**
1. App ni draft/internal testing ga yuklang (Billing faqat imzolangan Play buildda to'liq ishlaydi).
2. Monetize → In-app products → `pro_unlock` (Managed product / one-time).
3. Ixtiyoriy: Subscriptions → `pro_monthly`.
4. Settings → License testing ga o'z Google hisobingizni qo'shing.

**Kod oqimi:**
- `BillingManager.start()` — ilova ochilganda
- `launchPurchase()` — Pro dialogdan
- `onPurchasesUpdated` → `isPro = true` + acknowledge
- Product topilmasa / Billing yo'q (sideload) → test Pro ochiladi

**Muhim:** Sideload APK da Play Billing odatda ishlamaydi; internal testing track orqali sinang.
