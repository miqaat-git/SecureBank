#!/usr/bin/env bash
# Creates the SecureBank schema and the two least-privilege database accounts.
# Run once per environment by a MySQL administrator (docs/database-setup.md).
#
# Required environment (nothing is read from the repository):
#   SB_DB_ADMIN_USER, SB_DB_ADMIN_PASSWORD   MySQL administrator used for this one run
#   SB_APP_DB_PASSWORD                       password for the application account
#   SB_MIGRATE_DB_PASSWORD                   password for the migration account
# Optional (defaults in brackets):
#   SB_DB_HOST [localhost]  SB_DB_PORT [3306]  SB_DB_SCHEMA [securebank]
#   SB_APP_DB_USER [securebank_app]  SB_MIGRATE_DB_USER [securebank_migrate]
#   SB_DB_ACCOUNT_HOST [localhost]   host part of the two accounts
set -euo pipefail

: "${SB_DB_ADMIN_USER:?set SB_DB_ADMIN_USER}"
: "${SB_DB_ADMIN_PASSWORD:?set SB_DB_ADMIN_PASSWORD}"
: "${SB_APP_DB_PASSWORD:?set SB_APP_DB_PASSWORD}"
: "${SB_MIGRATE_DB_PASSWORD:?set SB_MIGRATE_DB_PASSWORD}"

HOST="${SB_DB_HOST:-localhost}"
PORT="${SB_DB_PORT:-3306}"
SCHEMA="${SB_DB_SCHEMA:-securebank}"
APP_USER="${SB_APP_DB_USER:-securebank_app}"
MIGRATE_USER="${SB_MIGRATE_DB_USER:-securebank_migrate}"
ACCOUNT_HOST="${SB_DB_ACCOUNT_HOST:-localhost}"

name_ok()  { [[ "$1" =~ ^[A-Za-z0-9._%-]+$ ]]; }
# Passwords are restricted so they can be substituted into SQL safely; use a long random one.
pass_ok()  { [[ "$1" =~ ^[A-Za-z0-9._+=-]{16,}$ ]]; }

for v in "$SCHEMA" "$APP_USER" "$MIGRATE_USER" "$ACCOUNT_HOST"; do
  name_ok "$v" || { echo "Invalid name or host value: $v" >&2; exit 1; }
done
[[ "$SCHEMA" != *_* ]] || { echo "Schema name must not contain '_' (it is a wildcard in GRANT)." >&2; exit 1; }
[[ "$APP_USER" != "$MIGRATE_USER" ]] || { echo "The application and migration accounts must differ." >&2; exit 1; }
pass_ok "$SB_APP_DB_PASSWORD"     || { echo "SB_APP_DB_PASSWORD must be 16+ chars of [A-Za-z0-9._+=-]." >&2; exit 1; }
pass_ok "$SB_MIGRATE_DB_PASSWORD" || { echo "SB_MIGRATE_DB_PASSWORD must be 16+ chars of [A-Za-z0-9._+=-]." >&2; exit 1; }

TEMPLATE="$(cd "$(dirname "$0")" && pwd)/accounts.sql.template"

# The administrator password goes through MYSQL_PWD, and the SQL (which contains the account
# passwords) goes through stdin, so no secret appears on a command line.
export MYSQL_PWD="$SB_DB_ADMIN_PASSWORD"
sed -e "s|@SCHEMA@|$SCHEMA|g" \
    -e "s|@APP_USER@|$APP_USER|g" \
    -e "s|@MIGRATE_USER@|$MIGRATE_USER|g" \
    -e "s|@HOST@|$ACCOUNT_HOST|g" \
    -e "s|@APP_PASSWORD@|$SB_APP_DB_PASSWORD|g" \
    -e "s|@MIGRATE_PASSWORD@|$SB_MIGRATE_DB_PASSWORD|g" \
    "$TEMPLATE" | mysql -h "$HOST" -P "$PORT" -u "$SB_DB_ADMIN_USER"

echo "Schema '$SCHEMA' and accounts '$APP_USER' and '$MIGRATE_USER' are ready."
