# Play Console → Policy → App content: tayyor javoblar

Quyidagilar kod va maxfiylik siyosatiga mos yozilgan. Console'dagi savol matni o'zgargan bo'lsa, ma'nosiga qarab tanlang.

## 1. Maxfiylik siyosati
URL: `https://<github-foydalanuvchi>.github.io/<repo>/` (brauzerda kirishsiz ochilishi shart). Sahifada email, mikrofon, AdMob, AI provayderlar va App Open reklamasi yozilgan.

## 2. Ads (reklama)
**Ha, ilovada reklama bor** (banner, interstitial, rewarded, App Open — AdMob).

## 3. App access (kirish)
Cheklangan funksiya: **bor**. Tarjima uchun AI API kaliti kerak. «Instructions» maydoniga:

Ilova hisob/parol so'ramaydi. Tarjima uchun API kalit kerak:
1. Sozlamalar → Tarjima (AI) → Gemini → kalitni kiriting: `<SIZNING TEST KALITINGIZ>`.
2. Sozlamalar → Yuklab olinadigan komponentlar → Whisper va ovoz modelini yuklang (Wi-Fi).
3. Bosh ekran → Video tanlash (10–20 soniyalik, inglizcha nutq) → Tarjimani tasdiqlang → natija videosi chiqadi.
Pro xarid: Play test xaridi (license tester).

Eslatma: test kalit uchun alohida Google AI Studio kaliti oching va tekshiruvdan keyin o'chiring.

## 4. Target audience va content
- Yosh guruhi: **13+** (13–15, 16–17, 18+ ni belgilang). «Bolalar uchun» **emas** (Families siyosati qo'llanmaydi).
- Ilova bolalarni jalb qilmaydi.

## 5. Content rating (IARC)
- Kategoriya: **Utility / Productivity**.
- Zo'ravonlik, jinsiy kontent, haqorat, qimor, giyohvand — barchasi **Yo'q**.
- Foydalanuvchilar o'zaro kontent almashadimi: **Yo'q** (ijtimoiy funksiya yo'q).
- Joylashuv ulashish: **Yo'q**. Raqamli xarid: **Ha**.
- Sun'iy intellekt (agar so'ralsa): ilova tarjima va ovozni AI bilan yaratadi — **Ha**, foydalanuvchi o'z videosi uchun.

## 6. Data safety
Umumiy: ma'lumot yig'iladi va ulashiladi — **Ha**. Uzatishda shifrlanadi — **Ha** (HTTPS). Hisob yo'q, o'chirish so'rovi mexanizmi: ilovani o'chirish/Sozlamalardagi tozalash (Console «Delete data» havolasi so'ramasa, bo'sh qoldiring).

| Toifa | Yig'iladimi / ulashiladimi | Maqsad | Majburiymi |
|---|---|---|---|
| Device or other IDs (Advertising ID) | Ha / Ha (Google AdMob) | Advertising, fraud prevention | Ixtiyoriy emas |
| Approximate location (IP asosida) | Ha / Ha (AdMob) | Advertising | Ixtiyoriy emas |
| App activity → App interactions | Ha / Ha (AdMob) | Advertising, analytics | Ixtiyoriy emas |
| App info and performance → Diagnostics | Ha / Ha (AdMob) | Analytics, fraud prevention | Ixtiyoriy emas |
| App activity → Other user-generated content (transkripsiya/tarjima matni) | Ha / Ha (foydalanuvchi tanlagan AI provayder) | App functionality | Ha, tarjima uchun |

Yig'ilmaydi (telefonda qoladi): video, audio, mikrofon yozuvi, kontaktlar, fotolar, aniq joylashuv, shaxsiy ma'lumot (ism, email), to'lov ma'lumotlari (Google Play'da).
Purchase history: ilova xaridni faqat Google Play Billing orqali telefonda tekshiradi, o'z serverimizga yubormaydi — alohida deklaratsiya shart emas.

## 7. Foreground service (Policy → App content → Foreground service permissions)
Ikkita tur deklaratsiya qilinadi. Har biriga video (demo) kerak: ekran yozuvida dublyaj boshlang, bildirishnomada «Dublyaj ishlamoqda» ko'rinsin, ilovani fonga olib chiqing.

- **Data sync** (`FOREGROUND_SERVICE_DATA_SYNC`): «Foydalanuvchi so'rovi bilan nutq va ovoz modellarini (Whisper, ONNX) internetdan yuklab olish va import qilish. Ish bir necha daqiqa davom etadi, bildirishnomada jarayon va «Bekor qilish» tugmasi ko'rinadi. Tizim vaqt limitiga yetsa (onTimeout) servis to'xtatiladi.»
- **Media processing** (`FOREGROUND_SERVICE_MEDIA_PROCESSING`, Android 15+): «Foydalanuvchi tanlagan videoni telefonda qayta ishlash: nutqni matnga aylantirish, ovoz sintezi va video bilan birlashtirish (MP4). Jarayon foydalanuvchi ilovadan chiqib ketsa ham tugashi uchun kerak; bildirishnomada jarayon va «Bekor qilish» ko'rinadi.»

## 8. Ruxsatlar asoslari
- `RECORD_AUDIO`: faqat «O'zim» ovozimni yozish tugmasi bosilganda; ruxsat bosqichma-bosqich (runtime) so'raladi; yozuv telefonda qoladi.
- `POST_NOTIFICATIONS`: dublyaj jarayoni va tugashi haqida bildirishnoma.
- `FOREGROUND_SERVICE*`, `WAKE_LOCK`: uzoq jarayon paytida qurilma uxlab qolmasligi uchun.
- `INTERNET`: tarjima, model yuklash, reklama, xarid.
- `AD_ID`: AdMob (reklama identifikatori). «Advertising ID» deklaratsiyasida: **Ha**, maqsad — Advertising.
- Xavfli/cheklangan ruxsatlar (SMS, kontakt, joylashuv, barcha fayllar, to'liq ekran niyat) — **ishlatilmaydi**.

## 9. Boshqa deklaratsiyalar
- Government apps: Yo'q. Financial features: Yo'q. Health: Yo'q. News: Yo'q.
- Advertising ID: Ha (AdMob).
- AI-generated content: ilova AI tarjima/ovoz yaratadi. Foydalanuvchi nomaqbul kontent haqida Sozlamalar → «Nomaqbul kontent haqida xabar berish» orqali xabar bera oladi (email ilovasi ochiladi — to'liq ilova ichida emas; xavf haqida 03-chiqarish-tartibi.md ga qarang).
