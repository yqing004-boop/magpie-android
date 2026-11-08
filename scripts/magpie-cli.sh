#!/system/bin/sh
# ============================================================
# magpie 命令行包装脚本
# 自动补上 magpie 在 Android 上需要、但交互终端里没有的环境变量:
#   HOME          数据目录 (默认是 /, 只读且会写失败)
#   SSL_CERT_FILE 系统 CA 证书 (静态 Go 找不到)
#   MAGPIE_BUN    Android 版 Bun (magpie 自带的 glibc 版跑不起来)
# 用法: magpie [参数...]   与官方 magpie 完全一致
# ============================================================

MAGPIE_DATA=/data/adb/magpie/data
REAL_BIN=/data/adb/magpie/bin/magpie

# 数据目录 (首次使用时自动建好)
mkdir -p "$MAGPIE_DATA" 2>/dev/null
export HOME="$MAGPIE_DATA"
export MAGPIE_HOME="$MAGPIE_DATA"

# CA 证书: 没有就现场从系统证书合并一份
CA_BUNDLE="$MAGPIE_DATA/certs/ca-bundle.crt"
if [ ! -s "$CA_BUNDLE" ] && [ -d /system/etc/security/cacerts ]; then
  mkdir -p "$MAGPIE_DATA/certs" 2>/dev/null
  cat /system/etc/security/cacerts/*.0 > "$CA_BUNDLE" 2>/dev/null
fi
[ -s "$CA_BUNDLE" ] && export SSL_CERT_FILE="$CA_BUNDLE"

# Android 版 Bun (插件用)
for _b in "$MAGPIE_DATA"/.cache/magpie/bun/*/magpie-bun; do
  [ -x "$_b" ] && { export MAGPIE_BUN="$_b"; break; }
done

# 网关地址 (和服务保持一致的默认值)
[ -n "$MAGPIE_ADDR" ] || export MAGPIE_ADDR="0.0.0.0:3425"

# 上游 DNS: magpie 在 Android 上默认连 ::1:53; 本模块用 DNS_LOCAL_HOSTNAMES
# 让它直接查公网 DNS, 不占用 53 端口 (避免与系统热点冲突)
_mp_conf="/data/adb/magpie/scripts/magpie.config"
# shellcheck disable=SC1090
[ -f "$_mp_conf" ] && . "$_mp_conf" 2>/dev/null
export DNS_LOCAL_HOSTNAMES="${DNS_LOCAL_HOSTNAMES:-${MAGPIE_DNS:-223.5.5.5 119.29.29.29 8.8.8.8}}"
export RES_OPTIONS="${RES_OPTIONS:-timeout:2 attempts:2}"

exec "$REAL_BIN" "$@"
