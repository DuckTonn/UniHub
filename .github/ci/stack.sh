#!/usr/bin/env bash
set -euo pipefail

: "${UNIHUB_ENV_FILE:?Set UNIHUB_ENV_FILE to a disposable test env file}"
: "${COMPOSE_PROJECT_NAME:?Set a disposable COMPOSE_PROJECT_NAME}"
export COMPOSE_PROFILES=messaging
export MSYS_NO_PATHCONV=1
export PYTHONDONTWRITEBYTECODE=1
base=http://127.0.0.1:18080

compose() {
    docker compose --env-file "$UNIHUB_ENV_FILE" -f docker-compose.yml -f .github/ci/compose.yml -f ops/compose.monitoring.yml "$@"
}

make_test_env() {
    umask 077
    python - <<'PY'
import os
import secrets
from pathlib import Path

path = Path(os.environ["UNIHUB_ENV_FILE"])
with path.open("x", encoding="utf-8") as env:
    env.write(
        "DB_URL=jdbc:postgresql://postgres:5432/unihub\n"
        "DB_USERNAME=unihub\n"
        f"DB_PASSWORD={secrets.token_hex(20)}\n"
        f"REDIS_PASSWORD={secrets.token_hex(16)}\n"
        f"JWT_ACCESS_SECRET={secrets.token_hex(16)}\n"
        f"JWT_REFRESH_SECRET={secrets.token_hex(16)}\n"
        "NOTIFICATION_EMAIL_ENABLED=false\n"
        "NOTIFICATION_TELEGRAM_ENABLED=false\n"
        "LLM_PROVIDER=mock\n"
        "NOTIFICATION_OUTBOX_ENABLED=true\n"
        f"RABBITMQ_PASSWORD={secrets.token_hex(16)}\n"
        "FRONTEND_URL=https://client.example\n"
        "FRONTEND_PORT=127.0.0.1:18080\n"
    )
PY
}

smoke() {
    user_id() {
      compose exec -T postgres psql -U unihub -d unihub -Atc \
        "SELECT id FROM users WHERE role = '$1' AND status = 'ACTIVE' ORDER BY id LIMIT 1"
    }
    compose exec -T backend wget -q -O - http://ai-service:8000/health | grep -Fx '{"status":"ok"}'
    compose exec -T frontend wget -q -O /dev/null --header 'X-Forwarded-For: 192.0.2.10' http://127.0.0.1/api/test
    compose exec -T redis sh -ec 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli EXISTS ratelimit:ip:127.0.0.1' | grep -Fx 1
    compose exec -T redis sh -ec 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli EXISTS ratelimit:ip:192.0.2.10' | grep -Fx 0
    export IDENTITY_FIXTURE_PASSWORD="$(python -c 'import secrets; print(secrets.token_hex(16))')"
    compose exec -T postgres psql -v ON_ERROR_STOP=1 -U unihub -d unihub -v "fixture_password=$IDENTITY_FIXTURE_PASSWORD" <<'SQL'
    INSERT INTO users (full_name, email, password, role, status)
    VALUES ('Contract Student', 'web-login@example.test', crypt(:'fixture_password', gen_salt('bf')), 'STUDENT', 'ACTIVE'),
           ('Contract Staff', 'app-login@example.test', crypt(:'fixture_password', gen_salt('bf')), 'STAFF', 'ACTIVE');
SQL
    export OUTBOX_FIXTURE_WORKSHOP="$(compose exec -T postgres psql -v ON_ERROR_STOP=1 -U unihub -d unihub -qAtc "
      INSERT INTO workshops (title, total_slots, remaining_slots, price, status, room_id,
           registration_start_time, registration_end_time, start_time, end_time)
      VALUES ('Phase 2 outbox smoke fixture', 1, 1, 0, 'PUBLISHED', (SELECT min(id) FROM rooms),
           now() - interval '1 hour', now() + interval '1 hour',
           now() + interval '1 day', now() + interval '2 days')
      RETURNING id")"
    export PAYMENT_FIXTURE_WORKSHOP="$(compose exec -T postgres psql -v ON_ERROR_STOP=1 -U unihub -d unihub -qAtc "
      INSERT INTO workshops (title, total_slots, remaining_slots, price, status, room_id,
           registration_start_time, registration_end_time, start_time, end_time)
      VALUES ('Phase 4 payment smoke fixture', 1, 1, 10000, 'PUBLISHED', (SELECT min(id) FROM rooms),
           now() - interval '1 hour', now() + interval '1 hour',
           now() + interval '3 days', now() + interval '4 days')
      RETURNING id")"
    python .github/ci/gateway_smoke.py http://127.0.0.1:18080 "$UNIHUB_ENV_FILE" "$(user_id STUDENT)" "$(user_id ADMIN)"
    compose exec -T postgres psql -U unihub -d unihub -Atc "
      SELECT count(*) FROM registration_notification_outbox o
      JOIN registrations r ON r.id = o.registration_id
      WHERE r.workshop_id = $OUTBOX_FIXTURE_WORKSHOP
        AND o.published_at IS NOT NULL AND o.delivered_at IS NOT NULL" | grep -Fx 1
}

restore_drill() {
    local query="SELECT concat_ws(':', (SELECT count(*) FROM users), (SELECT count(*) FROM workshops),
        (SELECT count(*) FROM registrations), (SELECT count(*) FROM payments),
        (SELECT count(*) FROM flyway_schema_history WHERE success))"
    compose exec -T postgres createdb -U unihub unihub_restore_drill
    compose exec -T postgres pg_dump -U unihub --format=custom unihub |
        compose exec -T postgres pg_restore -U unihub --exit-on-error --no-owner --no-acl -d unihub_restore_drill
    [[ "$(compose exec -T postgres psql -U unihub -Atc "$query" unihub)" == \
       "$(compose exec -T postgres psql -U unihub -Atc "$query" unihub_restore_drill)" ]]
    echo "PostgreSQL restore preserved fixture and schema counts."
}

operations() {
    compose exec -T prometheus promtool check config /etc/prometheus/prometheus.yml
    python .github/ci/operations_smoke.py "$base" online
    restore_drill

    compose stop redis
    python .github/ci/operations_smoke.py "$base" redis-down
    compose up -d --no-build --wait redis
    python .github/ci/operations_smoke.py "$base" recovered
}

case "${1:-}" in
    start)
        make_test_env
        compose config --quiet
        for attempt in 1 2 3; do
            if compose build; then break; fi
            if [[ "$attempt" == 3 ]]; then exit 1; fi
            sleep "$((attempt * 10))"
        done
        compose up -d --no-build --wait --wait-timeout 180
        ;;
    smoke) smoke ;;
    operations) operations ;;
    logs) compose logs --no-color --tail=80 ;;
    down) if [[ -f "$UNIHUB_ENV_FILE" ]]; then compose down -v; fi ;;
    image) compose images -q "$2" ;;
    *) echo "Usage: $0 {start|smoke|operations|logs|down|image}" >&2; exit 2 ;;
esac
