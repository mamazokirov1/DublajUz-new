# Play'ga chiqarish: audit natijasi va tartib

## A. Audit: nima mos, nima tuzatildi
| Talab | Holat |
|---|---|
| targetSdk 36 (Android 16, 2026-08-31 dan majburiy) | Mos (36) |
| Billing Library 8.x | Mos (8.0.0) |
| 64-bit | Mos (arm64-v8a). x86/emulyator qurilmalar Play ro'yxatida chiqmaydi |
| 16 KB sahifa hajmi | CI `tools/check_16kb.py` tekshiradi; ONNX 1.22 mos. Whisper AAR natijasini Actions logida ko'ring |
| AAB va Play App Signing | CI AAB yig'adi (upload key Secrets orqali) |
| Self-update / sinov Pro / bulut TTS | Release'da o'chiq (`SELF_UPDATE=false`, `ALLOW_TEST_UNLOCK=false`, `CLOUD_TTS=false`); `REQUEST_INSTALL_PACKAGES` yo'q |
| Xavfli ruxsatlar | Yo'q; faqat mikrofon (runtime, ixtiyoriy) |
| Foreground service turlari va timeout | Manifestda e'lon qilingan, `onTimeout` bor; Console deklaratsiyasi — 02-app-content.md |
| Maxfiylik siyosati | **Tuzatildi:** `docs/index.html` eskirgan edi (mikrofon, boshqa AI provayderlar, GitHub model yuklash, App Open yo'q edi). Endi `PRIVACY.md` bilan bir xil; eski GitHub issues havolasi olib tashlandi |
| Ilova ichida siyosat havolasi, UMP roziligi | Bor (Sozlamalar → Ilova haqida) |
| AI kontent haqida xabar berish | **Qo'shildi:** Sozlamalar → «Nomaqbul kontent haqida xabar berish» |
| Reklama (App Open, UMP, Pro'da yo'q) | Mos; birinchi ishga tushishda ko'rsatilmaydi |
| Do'kon rasmlari | **Tayyorlandi:** `assets/icon-512.png`, `assets/feature-graphic-1024x500.png` |
| Do'kon matnlari | **Tayyorlandi:** `01-dokon-matnlari.md` |

## B. Hal qilishingiz shart bo'lgan masalalar (kod bilan hal bo'lmaydi)
1. **Ovoz modeli litsenziyasi.** `vits_uzbek.onnx` qaysi asosda o'qitilgan? Meta MMS asosida bo'lsa CC-BY-NC (tijoriy emas): reklama va Pro xarid bilan mos kelmaydi, Play'ga qo'yish huquqiy xavf. Model egasidan yozma ruxsat yoki tijoriy litsenziyali model kerak. Whisper modellari MIT — muammo yo'q.
2. **Repo egasi va email.** Maxfiylik siyosati va Play'dagi aloqa emaili `shoirjon0123@gmail.com`. Bu sizniki ekanini tekshiring. Kodda standart repo `mamazokirov1/DublajUz-new` yozilgan; CI haqiqiy repo nomini o'zi uzatadi, lekin GitHub Pages URL'i haqiqiy repo uchun ishlashini brauzerda tekshiring.
3. **Repo ochiq (public)** bo'lishi kerak: GitHub Pages (bepul) va `models-v1` yuklashi uchun.
4. **Tekshiruvchi uchun test AI kaliti** (App access).
5. **AI kontent xabari ilova ichida to'liq emas.** Play siyosati «ilovadan chiqmasdan» xabar berishni talab qiladi; hozirgi tugma email ilovasini ochadi. Siyosat misollari chatbot va matn/rasm/video generatsiyasini sanaydi, TTS dublyaj aniq tilga olinmagan, shuning uchun xavf past. Rad etilsa, yechim: oddiy server/Google Form'ga ilova ichidan POST.
6. **Yangi shaxsiy akkaunt** (2023-11-13 dan keyin ochilgan): Production'dan oldin yopiq testda **kamida 12 tester, uzluksiz 14 kun** bo'lishi shart ([Play yordam](https://support.google.com/googleplay/android-developer/answer/14151465)). Tashkilot akkauntiga bu qo'llanmaydi.

## C. Tartib (qadamma-qadam)
1. Kodni yuklang (Termux buyruqlari oldingi xabarda), Actions yashil bo'lishini kuting. Xato bo'lsa logni yuboring.
2. Secrets: `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (majburiy); `ADMOB_*` 5 ta (daromad uchun).
3. Settings → Pages → `main` / `/docs`. URL'ni brauzerda oching.
4. Play Console: ilova yarating → «App content» bo'limini 02-app-content.md bo'yicha to'ldiring.
5. Monetize → `pro_unlock` mahsulotini yarating va faollashtiring; License testing'ga o'z Gmail'ingizni qo'shing.
6. Main store listing: 01-dokon-matnlari.md va `assets/` rasmlari + o'z skrinshotlaringiz.
7. Internal testing: AAB yuklang, o'zingiz sinab ko'ring (dublyaj, xarid, reklama, mikrofon).
8. Closed testing: 12 tester, 14 kun (shaxsiy akkaunt bo'lsa). Test Gmail'larini Console'da ro'yxatga qo'shing.
9. Production uchun ariza → ko'rib chiqish (odatda bir necha kundan 1–2 haftagacha).
10. AdMob: ilova Play'da chiqqach AdMob'da ilovani Play do'koniga bog'lang va app-ads.txt (veb-sayt ko'rsatsangiz) qo'shing.

## D. Yuborishdan oldingi qisqa tekshiruv (Internal testing'da)
- Birinchi ochilganda App Open reklamasi chiqmaydi; ikkinchi ochilishda (2 daqiqadan keyin) chiqadi.
- Pro xariddan keyin reklama yo'qoladi.
- Mikrofon ruxsatini rad etganda ilova yiqilmaydi.
- Bildirishnomada «Bekor qilish» ishlaydi; ilova fonda dublyajni tugatadi.
- Sozlamalar → Maxfiylik siyosati ochiladi; «Nomaqbul kontent» tugmasi email ochadi.
