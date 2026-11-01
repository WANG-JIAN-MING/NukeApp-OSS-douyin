SKIPUNZIP=1
[ "$BOOTMODE" = true ] || abort "Install from the running Root manager"
[ "$API" -ge 27 ] || abort "Android API 27 or newer is required"
case "$ARCH" in
  arm64) ABI=arm64-v8a ;;
  *) abort "NukeApp-OSS Zygisk supports arm64-v8a only" ;;
esac
ui_print "- NukeApp-OSS: fixture-only Zygisk mode"
ui_print "- Enable Zygisk and reboot after installation"
ui_print "- Use one NukeApp-OSS loader per fixture process"
DEX_FILES=$(unzip -l "$ZIPFILE" | awk '$NF ~ /^classes([2-9]|[1-9][0-9]+)?\.dex$/ {print $NF}')
[ -n "$DEX_FILES" ] || abort "Missing DEX payload"
mkdir -p "$MODPATH/zygisk" "$MODPATH/payload"
unzip -p "$ZIPFILE" module.prop > "$MODPATH/module.prop" || abort "Missing module.prop"
for DEX in $DEX_FILES; do
  unzip -p "$ZIPFILE" "$DEX" > "$MODPATH/payload/$DEX" || abort "Cannot extract $DEX"
  [ -s "$MODPATH/payload/$DEX" ] || abort "Empty $DEX"
done
unzip -p "$ZIPFILE" "lib/$ABI/libnuke_zygisk.so" > "$MODPATH/zygisk/$ABI.so" || abort "Missing native library for $ABI"
# The libraries the module's code loads in the host (DexKit, the protocol core, the audio codec, QuickJS). The Zygisk
# entry and the Compose-only library stay out. The host gets them from here, so no module APK has to be installed.
NATIVE_FILES=$(unzip -l "$ZIPFILE" | awk -v dir="lib/$ABI/" 'index($NF, dir) == 1 && $NF ~ /\.so$/ {print $NF}')
mkdir -p "$MODPATH/natives"
for NATIVE in $NATIVE_FILES; do
  NAME=${NATIVE##*/}
  case "$NAME" in libnuke_zygisk.so|libandroidx.graphics.path.so) continue ;; esac
  unzip -p "$ZIPFILE" "$NATIVE" > "$MODPATH/natives/$NAME" || abort "Cannot extract $NAME"
  [ -s "$MODPATH/natives/$NAME" ] || abort "Empty $NAME"
done
[ -s "$MODPATH/payload/classes.dex" ] || abort "Missing primary DEX"
[ -s "$MODPATH/zygisk/$ABI.so" ] || abort "Empty native library"
set_perm_recursive "$MODPATH" 0 0 0755 0644
