# Play Market: qadamma-qadam (telefondan ham bajariladi)

## A. Bir marta: upload key
1. Termux: `pkg install openjdk-17`, so'ng loyiha papkasida `bash tools/make_upload_key.sh`.
2. `~/dublaj-upload/` ichidagi `dublaj-upload.jks` va `parol.txt` ni **zaxiraga** saqlang (yo'qolsa — yangilanish chiqarolmaysiz).
3. GitHub → repo → Settings → Secrets and variables → Actions → New secret:
   `KEYSTORE_B64` (KEYSTORE_B64.txt ichidagi matn), `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS` = `dublaj-upload`.
4. Eski `app/dublaj-sign.keystore` faqat TEST APK uchun. Play uchun ishlatilmaydi.

> **AAB nega chiqmagan edi?** CI `KEYSTORE_B64` Secret yo'q bo'lsa release bosqichini jimgina o'tkazib yuborardi. Endi Secret yo'q bo'lsa ish **qizil** bo'ladi va xabarda qaysi Secret yetishmayotgani yoziladi. 4 ta Secret (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`) qo'shilgach — Actions → Run workflow — AAB `DublajUz-release-aab` artifaktida chiqadi.

## B. AdMob (ixtiyoriy, daromad uchun)
1. admob.google.com → App qo'shing → App ID. Banner, Interstitial, Rewarded va **App Open** ad unit yarating.
2. Secrets: `ADMOB_APP_ID`, `ADMOB_BANNER_ID`, `ADMOB_INTERSTITIAL_ID`, `ADMOB_REWARDED_ID`, `ADMOB_APP_OPEN_ID`.
3. Berilmasa release ham **test reklamalari** bilan chiqadi (Play'ga yuklash mumkin, lekin pul tushmaydi).
4. AdMob → Privacy & messaging → **GDPR** xabarini yarating va ilovaga bog'lang (UMP shu yerdan ishlaydi).

## C. Play Console da mahsulot
- Monetize → In-app products → ID: **`pro_unlock`** (bir martalik). Faol qiling.
- (Ixtiyoriy) Subscriptions → ID: `pro_monthly`.
- Setup → License testing: o'z Gmail'ingizni qo'shing (test xarid uchun).

## D. Maxfiylik siyosati URL
1. `PRIVACY.md` va `docs/index.html` v33 da yangilangan (mikrofon, AI provayderlar, App Open). Emailni o'zingiznikiga almashtirsangiz, ikkalasida ham o'zgartiring.
2. GitHub → Settings → Pages → Branch: `main`, Folder: `/docs` → Save.
3. URL: `https://<foydalanuvchi>.github.io/DublajUz-new/` — Play Console → App content → Privacy policy.

## E. App content deklaratsiyalari
- **Data safety:** yig'iladi/ulashiladi — *Device or other IDs* (Advertising ID, AdMob), *App activity/Diagnostics* (AdMob), *Purchase history* (Play). Matn (transkripsiya) Gemini'ga yuboriladi — "User content: text" ni ham belgilang. Ma'lumotlar shifrlangan kanalda (HTTPS) uzatiladi; o'chirish uchun akkaunt yo'q.
- **Ads:** "Ha, ilovada reklama bor".
- **Target audience:** 13+ (bolalar uchun emas).
- **Content rating:** so'rovnomani to'ldiring. AI bilan yaratilgan ovoz borligini ko'rsating.
- **Foreground service:** modellarni yuklash — `dataSync`; video qayta ishlash — `mediaProcessing` (Android 15+) / `dataSync` (10–14). Turlari `mediaProcessing` (Android 15+) va `dataSync` (Android 10–14). Tavsif: "Foydalanuvchi tanlagan videoni telefonda matnga aylantirish, tarjima va ovoz sintezi — bir necha daqiqa davom etadi; bildirishnomada ko'rinadi". Qisqa video (demo) so'rashi mumkin.
- **App access:** ilova ishlashi uchun Gemini API kaliti va ovoz model fayllari kerak — tekshiruvchiga qo'llanma/test kalit bering (pastdagi "Muhim" ga qarang).
- **AI-generated content:** foydalanuvchi muammo/noo'rin natija haqida xabar bera olishi kerak — Sozlamalardagi "muammo hisoboti" shu uchun.

## F. Yuklash
1. Actions → eng yangi run → Artifacts → **DublajUz-release-aab** (zip) → ichidan `.aab` ni oling.
2. Play Console → Testing → **Internal testing** → Create release → AAB'ni yuklang → o'zingiz sinab ko'ring (xarid ham).
3. Yangi shaxsiy akkaunt bo'lsa, ommaviy (Production) chiqarishdan oldin **yopiq test** (testchilar soni va kunlari) talab qilinishi mumkin — Console'da ko'rsatadi.
4. Har yangi yuklashda `versionCode` oshishi kerak — CI buni avtomatik qiladi (run raqami).

## Muhim (kod xatosi emas, mahsulot masalasi)
- **Modellar (hal qilindi, lekin siz fayllarni yuklashingiz kerak):** ilova Whisper'ni Hugging Face'dan, o'zbekcha ovoz modelini (`vits_uzbek.onnx`, `uz_tts_base.json`) repo'ning **`models-v1`** relizidan yuklaydi. Reliz yaratish: `bash tools/publish_models.sh /sdcard/Download/vits_uzbek.onnx /sdcard/Download/uz_tts_base.json` (Termux'da `pkg install gh`, `gh auth login`). Yoki GitHub saytida: Releases → Draft a new release → tag `models-v1` → ikkala faylni biriktiring → **Set as a pre-release** → Publish. Repo ochiq (public) bo'lishi shart.
- **Gemini kaliti:** foydalanuvchi o'z kalitini kiritadi. Oddiy odamlar uchun qiyin; keyinchalik o'z backend'ingiz (kalit yashirin) yoki boshqa yechim o'ylash kerak.
- **Litsenziya:** ommaviy repo'ga qo'yish — bu tarqatish. VITS modeli va Whisper modellari litsenziyasi tijoriy ilovaga ruxsat berishini tekshiring.
- **Pul olish:** Pro xaridni sotish uchun Play Console'da to'lov profili (merchant) kerak va mamlakatingiz qo'llab-quvvatlanishi lozim — Console'da tekshiring.

## Qo'shimcha (v11): Data safety va Pro
- **Data safety:** "Data shared" bo'limiga qo'shing: matn (transkripsiya) — uchinchi tomon AI provayderlarga (foydalanuvchi o'zi tanlaydi va o'z kaliti bilan). Maqsad: ilova funksiyasi.
- **Pro (bir martalik `pro_unlock`):** cheksiz dublyaj, reklamasiz, cheksiz navbat (bepulda 2), uzun matn → ovoz (bepulda 1 500, Pro 200 000 belgi), 5 ta rang mavzusi, ovozni avto-saqlash.
- **Reklama:** banner barcha ekranlar pastida (Pro da yo'q); interstitial dublyaj tugagach, har natija uchun bir marta.
- **Test:** App access bo'limida tekshiruvchi uchun test API kalit (yoki Gemini bepul kaliti qo'llanmasi) bering.

## Bepul limitlar va mukofotli reklama (v12)
- Bepul: kuniga 3 ta dublyaj, **bitta video 15 daqiqagacha**. Uzun video tanlansa Pro'ga undovchi dialog chiqadi.
- Limit tugagach foydalanuvchi **5 ta mukofotli reklama** ko'rib 1 ta qo'shimcha imkoniyat oladi (`UsageStore.ADS_PER_BONUS`). Matn → ovoz ham shu imkoniyatdan sarflaydi.
- AdMob'da yangi **Rewarded** reklama birligi yarating va GitHub Secret qo'shing: `ADMOB_REWARDED_ID`. Berilmasa Google TEST reklamasi chiqadi.

## Qo'shimcha (v13): nimalar o'zgardi va nimalar qoldi
- Tuzatildi: tarjimaning qisqa variantlari (`alts`) diskka saqlanmasdi — ilova qayta ochilgach sig'dirish ishlamasdi.
- Tuzatildi: aralashmada ovoz qattiq qirqilardi (buzilish) — endi silliq limiter.
- Yangi sozlamalar: Audio sifati (Standart / Yuqori 44.1 kHz, 160 kbps), ayol/erkak/bola tembri siljishi.
- Yangi: Sozlamalar → Ilova haqida → "Maxfiylik siyosati" (`BuildConfig.PRIVACY_URL`). GitHub Pages yoqilganini tekshiring.
- Qoldi (qo'lda): VITS modeli litsenziyasi (MMS asosida bo'lsa CC-BY-NC — reklama/Pro bilan mos emas), AdMob/Billing ID lari, Data safety, yopiq test (shaxsiy akkaunt: 12 tester × 14 kun).

## v15 (1-qism): sifat
- Ovoz tezligi o'lchanadi (`VoiceCalibration`): tarjimaga "gapga necha belgi sig'adi" haqiqiy TTS tezligidan beriladi (avval qat'iy 16 belgi/s edi).
- Erkak/ayol avtomatik aniqlanadi (`PitchEstimator` + `VoiceRoles`): Sozlamalar → Ovoz sifati → "Dublyaj ovozi" ni to'g'ri tanlang.
- Ikkalasi ham videoni QAYTA aniqlash/tarjima qilganda ishlaydi.
- v15-3: dublyajdan keyin hisobot — tezlashtirilgan (>1.2×) yoki kechikkan (>0.8 s) gaplar tahrir ekranida belgilanadi; natija kartasida "Ko'rib chiqish va tuzatish" tugmasi. Bunday belgilar tasdiqlashni to'smaydi.

## v32: tahrirlash, ovoz usuli, ochilish reklamasi
- **Tarixdan tahrirlash:** Tarix → videoda «Tarjimani tahrirlash» (tarjima, rollar, ovoz usullari, yozilgan ovozlar tiklanadi; eski videolarda subtitrdan tiklanadi, asl video topilmasa tanlatiladi) va «Montaj / ovoz» (blokni surish va bitta ovozni qayta dublyaj qilish). Har dublyaj yonida `.dublaj` loyiha fayli va `.mproj/` papkasi saqlanadi (joy ~3–5 MB/daqiqa), video o'chirilsa ular ham o'chadi.
- **Ovoz usuli (har bir gap uchun):** tahrir ekranida Avto / ONNX / Gemini / O'zim. «Qayta dublyaj» tugmasi faqat o'zgargan gaplarni qayta ovozlaydi (qolganlari keshdan).
- **Nusxalash/joylash:** asl matn va tarjima yonida; joylagandan keyin «Qaytarish».
- **Ilova ochilganda reklama (App Open):** AdMob'da **App Open** birligi yarating → Secret `ADMOB_APP_OPEN_ID`. Berilmasa Google TEST reklamasi.
  - Ko'rsatilmaydi: Pro'da; birinchi ishga tushishda (onboarding tugamaguncha); boshqa to'liq ekran reklamadan keyin 15 s ichida.
  - Ko'rsatiladi: sovuq ochilganda (reklama 8 s ichida yuklansa) va ilova 60 s dan ko'p fonda bo'lib qaytganda; ikki marta orasida kamida 2 daqiqa.
  - Play siyosati: reklama kutilmagan joyda chiqmasligi kerak; App Open Google tomonidan aynan ilova ochilishi uchun ruxsat etilgan format. Data safety'da reklama deklaratsiyasi o'zgarmaydi.

## v33: Play audit
To'liq audit natijasi, do'kon matnlari, Data safety/Content rating javoblari va chiqarish tartibi `play/` papkasida:
- `play/01-dokon-matnlari.md`, `play/02-app-content.md`, `play/03-chiqarish-tartibi.md`, `play/assets/` (ikona va banner).
- Eng muhim ochiq masala: ovoz modeli litsenziyasi va yangi akkaunt uchun 12 tester × 14 kun yopiq test.
