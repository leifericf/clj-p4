# Helix Core (p4d) for clj-p4 integration tests.
#
# Builds locally; image is not published. Downloads p4d / p4 binaries
# directly from ftp.perforce.com (Perforce's apt repo's bookworm release
# was returning 404 at build time and they ship binaries only for
# linux26x86_64 anyway). Pinned to r24.1 so we exercise the -Mj JSON
# dispatch path against a current 2024.1+ server.
#
# Platform is forced to linux/amd64 — runs under Rosetta on Apple Silicon.

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

# Babashka — fixture entrypoint and seed scripts are written in Clojure.
ARG BB_VERSION=1.12.197
RUN curl -fsSL \
        "https://github.com/babashka/babashka/releases/download/v${BB_VERSION}/babashka-${BB_VERSION}-linux-amd64-static.tar.gz" \
    | tar -xz -C /usr/local/bin \
 && chmod +x /usr/local/bin/bb \
 && bb --version

# UTF-8 locale for unicode-content fixtures.
RUN sed -i 's/# en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen \
 && locale-gen
ENV LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8

# Server config: SSL off (test only), unicode mode on, password 'admin1234'.
ENV P4ROOT=/p4 P4PORT=1666 \
    P4USER=admin P4PASSWD=admin1234 \
    P4_TEST_FIXTURE=1

EXPOSE 1666
WORKDIR /p4

COPY entrypoint.bb /usr/local/bin/entrypoint.bb
COPY seed.bb       /usr/local/bin/seed.bb
RUN chmod +x /usr/local/bin/entrypoint.bb /usr/local/bin/seed.bb

ENTRYPOINT ["/usr/local/bin/entrypoint.bb"]
CMD ["serve"]
