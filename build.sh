#!/usr/bin/env bash
# Builds and signs the module. Needs an Android SDK android.jar, the Xposed API jar,
# and the AOSP build tools (aapt2, d8, zipalign, apksigner) -- all paths below.
set -euo pipefail

AJ=${AJ:?set AJ to an android.jar (compileSdk 36)}
XP=${XP:?set XP to xposed api-82.jar}
BT=${BT:?set BT to a dir containing aapt2, d8, zipalign, apksigner}
KS=${KS:?set KS to your keystore}
KS_PASS=${KS_PASS:?set KS_PASS}
ALIAS=${ALIAS:-clearall}
OUT=${OUT:-RecentsClearAll.apk}

rm -rf build && mkdir -p build/classes build/dex

"$BT/aapt2" compile --dir res -o build/res.zip
javac -source 8 -target 8 -nowarn -classpath "$AJ:$XP" -d build/classes $(find src -name '*.java')
"$BT/d8" --min-api 28 --release --output build/dex --lib "$AJ" --classpath "$XP" \
    $(find build/classes -name '*.class')
"$BT/aapt2" link -o build/base.apk -I "$AJ" --manifest AndroidManifest.xml \
    --min-sdk-version 28 --target-sdk-version 36 -A assets build/res.zip

cp build/base.apk build/unsigned.apk
cp build/dex/classes.dex .
# stored, not deflated: Android mmaps the dex straight out of the APK
zip -q -X -0 build/unsigned.apk classes.dex && rm -f classes.dex

"$BT/zipalign" -p -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$ALIAS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --out "$OUT" build/aligned.apk
"$BT/apksigner" verify "$OUT" && echo "built $OUT"
