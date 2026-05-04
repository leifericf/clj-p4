#!/usr/bin/env bash
# Initialise /p4 on first run; create the admin user; start p4d.
set -euo pipefail

mkdir -p "${P4ROOT}"
cd "${P4ROOT}"

INIT_MARKER="${P4ROOT}/.clj-p4-initialised"

start_p4d_bg() {
    p4d -p "tcp:${P4PORT}" -r "${P4ROOT}" -L "${P4ROOT}/log" -d
    # Wait for the listener.
    for _ in $(seq 1 30); do
        if p4 -p "tcp:localhost:${P4PORT}" info >/dev/null 2>&1; then
            return 0
        fi
        sleep 0.5
    done
    echo "p4d failed to come up" >&2
    return 1
}

stop_p4d() {
    if pgrep -x p4d >/dev/null; then
        p4 -p "tcp:localhost:${P4PORT}" -u "${P4USER}" \
           -P "${P4PASSWD}" admin stop || true
        sleep 1
    fi
}

if [[ ! -f "${INIT_MARKER}" ]]; then
    echo "[entrypoint] first-run initialisation"
    p4d -r "${P4ROOT}" -L "${P4ROOT}/log" -xi   # set unicode mode
    start_p4d_bg

    # Create the admin user (no password yet, so script-level).
    p4 -p "tcp:localhost:${P4PORT}" user -f -i <<EOF
User: ${P4USER}
Email: ${P4USER}@example.com
FullName: clj-p4 admin
EOF

    # Set a password. Use a heredoc — `yes | passwd` makes pipefail explode
    # with SIGPIPE on the producer side once passwd has read the two lines
    # it needs.
    p4 -p "tcp:localhost:${P4PORT}" -u "${P4USER}" passwd <<EOF
${P4PASSWD}
${P4PASSWD}
EOF

    # Run the seed script if present.
    if [[ -x /usr/local/bin/seed.sh ]]; then
        echo "[entrypoint] seeding fixtures"
        /usr/local/bin/seed.sh || {
            echo "[entrypoint] seed.sh failed (continuing)" >&2
        }
    fi

    stop_p4d
    touch "${INIT_MARKER}"
fi

case "${1:-serve}" in
    serve)
        echo "[entrypoint] starting p4d on tcp:${P4PORT}"
        exec p4d -p "tcp:${P4PORT}" -r "${P4ROOT}" -L "${P4ROOT}/log"
        ;;
    seed)
        start_p4d_bg
        /usr/local/bin/seed.sh
        stop_p4d
        ;;
    *)
        exec "$@"
        ;;
esac
