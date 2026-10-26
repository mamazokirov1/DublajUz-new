#!/data/data/com.termux/files/usr/bin/bash
# O'zbekcha ovoz modelini GitHub Release (models-v1) ga yuklaydi.
# Oldin (bir marta):  pkg install gh   va   gh auth login
# Ishlatish:  bash tools/publish_models.sh /sdcard/Download/vits_uzbek.onnx /sdcard/Download/uz_tts_base.json
# Fayl nomlari AYNAN shunday bo'lishi shart: vits_uzbek.onnx va uz_tts_base.json
set -e
REPO="${REPO:-mamazokirov1/DublajUz-new}"
TAG="${TAG:-models-v1}"
ONNX="$1"; CFG="$2"
if [ -z "$ONNX" ] || [ -z "$CFG" ]; then echo "Ishlatish: $0 <vits_uzbek.onnx> <uz_tts_base.json>"; exit 1; fi
[ "$(basename "$ONNX")" = "vits_uzbek.onnx" ] || { echo "Fayl nomi vits_uzbek.onnx bo'lsin"; exit 1; }
[ "$(basename "$CFG")" = "uz_tts_base.json" ] || { echo "Fayl nomi uz_tts_base.json bo'lsin"; exit 1; }

TMP="$(mktemp -d)"
cp "$ONNX" "$TMP/vits_uzbek.onnx"; cp "$CFG" "$TMP/uz_tts_base.json"
S1=$(stat -c %s "$TMP/vits_uzbek.onnx"); H1=$(sha256sum "$TMP/vits_uzbek.onnx" | cut -d' ' -f1)
S2=$(stat -c %s "$TMP/uz_tts_base.json"); H2=$(sha256sum "$TMP/uz_tts_base.json" | cut -d' ' -f1)
cat > "$TMP/models-manifest.json" <<JSON
{"files":{"vits_uzbek.onnx":{"size":$S1,"sha256":"$H1"},"uz_tts_base.json":{"size":$S2,"sha256":"$H2"}}}
JSON
cat "$TMP/models-manifest.json"; echo

if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
  gh release upload "$TAG" "$TMP/vits_uzbek.onnx" "$TMP/uz_tts_base.json" "$TMP/models-manifest.json" --clobber -R "$REPO"
else
  gh release create "$TAG" "$TMP/vits_uzbek.onnx" "$TMP/uz_tts_base.json" "$TMP/models-manifest.json" \
    --prerelease --title "Models v1 (ilova yuklab oladi, o'rnatmang)" \
    --notes "DublajUz ovoz modeli. Bu fayllarni ilova o'zi yuklab oladi." -R "$REPO"
fi
rm -rf "$TMP"
echo "TAYYOR: https://github.com/$REPO/releases/tag/$TAG"
