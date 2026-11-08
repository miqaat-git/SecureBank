#!/usr/bin/env bash
# Inspects the built WAR (run after: mvn clean package). Checks packaging facts for Phase 0.
set -uo pipefail
cd "$(dirname "$0")/.."
WAR=target/securebank.war
[ -f "$WAR" ] || { echo "FAIL: $WAR not found; run mvn clean package first"; exit 1; }
LIST="$(unzip -Z1 "$WAR")"
fail=0
check() { # description, command that must succeed
  if eval "$2"; then echo "PASS: $1"; else echo "FAIL: $1"; fail=1; fi
}
check "JSP is under WEB-INF/views"              'grep -q "^WEB-INF/views/phase0/proof.jsp$" <<<"$LIST"'
check "no JSP outside WEB-INF"                  '! grep -E "\.jsp$" <<<"$LIST" | grep -vq "^WEB-INF/"'
check "application class is packaged"           'grep -q "^WEB-INF/classes/com/securebank/SecureBankApplication.class$" <<<"$LIST"'
check "Jakarta JSTL jars are in WEB-INF/lib"    'grep -q "^WEB-INF/lib/jakarta.servlet.jsp.jstl" <<<"$LIST"'
check "no javax.servlet jars"                   '! grep -Ei "^WEB-INF/lib(-provided)?/.*javax[.]servlet" <<<"$LIST"'
check "no Flyway jars (migrations are a separate step)" '! grep -Ei "^WEB-INF/lib/flyway" <<<"$LIST"'
check "Tomcat is provided, not in WEB-INF/lib"  '! grep -Ei "^WEB-INF/lib/tomcat-embed" <<<"$LIST"'
check "no pom.xml descriptor inside the WAR"    '! grep -q "^META-INF/maven/" <<<"$LIST"'
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
unzip -q "$WAR" 'WEB-INF/classes/*' -d "$TMP" 2>/dev/null || true
check "no migration credential names in packaged resources" '! grep -rIEl "SB_MIGRATE|flyway\.(user|password)" "$TMP/WEB-INF/classes" >/dev/null 2>&1'
exit $fail
