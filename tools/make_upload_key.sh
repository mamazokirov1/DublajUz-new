#!/data/data/com.termux/files/usr/bin/bash
# Play Market uchun UPLOAD KEY yaratadi (Termux yoki Linux).
# Oldin:  pkg install openjdk-17   (Termux)
# Ishlatish:  bash tools/make_upload_key.sh
set -e
OUT="$HOME/dublaj-upload"
mkdir -p "$OUT"
KS="$OUT/dublaj-upload.jks"
if [ -f "$KS" ]; then
  echo "Kalit allaqachon bor: $KS  (o'chirmang, yo'qolsa yangilanish chiqara olmaysiz!)"
else
  read -r -s -p "Yangi parol o'ylab toping (kamida 8 belgi): " PASS; echo
  keytool -genkeypair -v -keystore "$KS" -alias dublaj-upload \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=DublajUz, O=DublajUz, C=UZ"
  echo "$PASS" > "$OUT/parol.txt"
fi
base64 -w0 "$KS" > "$OUT/KEYSTORE_B64.txt"
echo
echo "================ TAYYOR ================"
echo "Papka: $OUT"
echo "1) KEYSTORE_B64.txt ichidagi matnni GitHub > Settings > Secrets > Actions > KEYSTORE_B64 ga qo'ying"
echo "2) KEYSTORE_PASSWORD = parol.txt ichidagi parol"
echo "3) KEY_PASSWORD      = o'sha parol"
echo "4) KEY_ALIAS         = dublaj-upload"
echo "MUHIM: dublaj-upload.jks va parol.txt ni xavfsiz joyga nusxalab qo'ying (repoga YUKLAMANG)."
