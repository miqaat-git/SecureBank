#!/usr/bin/env bash
# Applies Flyway migrations with the MIGRATION account. This is the only place the migration
# credentials are used; the application and the WAR never see them.
#
# Required environment:
#   SB_MIGRATE_DB_URL        e.g. jdbc:mysql://localhost:3306/securebank
#   SB_MIGRATE_DB_USER, SB_MIGRATE_DB_PASSWORD
# Usage: scripts/migrate.sh [info|migrate|validate]   (default: migrate)
set -euo pipefail
: "${SB_MIGRATE_DB_URL:?set SB_MIGRATE_DB_URL}"
: "${SB_MIGRATE_DB_USER:?set SB_MIGRATE_DB_USER}"
: "${SB_MIGRATE_DB_PASSWORD:?set SB_MIGRATE_DB_PASSWORD}"
GOAL="${1:-migrate}"
case "$GOAL" in info|migrate|validate) ;; *) echo "Unsupported goal: $GOAL" >&2; exit 1;; esac
cd "$(dirname "$0")/.."
exec mvn -B -ntp "flyway:$GOAL"
