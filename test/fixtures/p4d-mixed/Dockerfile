# Helix Core (p4d) for the metadata-encoding integration tests
# (t9835/t9836). Same base as the primary `p4d` fixture but the server
# is NOT converted to unicode mode (no `p4d -xi`), so descriptions and
# usernames can be submitted with arbitrary client-locale bytes
# (CP-1252, Latin-1, mixed). Listens on a different port (1667) so it
# can run side-by-side with the primary fixture.

FROM --platform=linux/amd64 debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive
ARG P4_RELEASE=r24.1

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        ca-certificates curl locales tzdata procps \
 && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL \
        "https://ftp.perforce.com/perforce/${P4_RELEASE}/bin.linux26x86_64/p4d" \
        -o /usr/local/bin/p4d \
 && curl -fsSL \
        "https://ftp.perforce.com/perforce/${P4_RELEASE}/bin.linux26x86_64/p4" \
        -o /usr/local/bin/p4 \
 && chmod +x /usr/local/bin/p4d /usr/local/bin/p4 \
 && p4d -V | head -3

ARG BB_VERSION=1.12.197
RUN curl -fsSL \
        "https://github.com/babashka/babashka/releases/download/v${BB_VERSION}/babashka-${BB_VERSION}-linux-amd64-static.tar.gz" \
    | tar -xz -C /usr/local/bin \
 && chmod +x /usr/local/bin/bb

# Locale stays UTF-8 so the bb seed script itself can read its own
# source. The server, however, runs in non-unicode mode and accepts
# non-UTF-8 metadata.
RUN sed -i 's/# en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen \
 && locale-gen
ENV LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8

ENV P4ROOT=/p4 P4PORT=1667 \
    P4USER=admin P4PASSWD=admin1234 \
    P4_TEST_FIXTURE=1

EXPOSE 1667
WORKDIR /p4

COPY entrypoint.bb /usr/local/bin/entrypoint.bb
COPY seed.bb       /usr/local/bin/seed.bb
RUN chmod +x /usr/local/bin/entrypoint.bb /usr/local/bin/seed.bb

ENTRYPOINT ["/usr/local/bin/entrypoint.bb"]
CMD ["serve"]
