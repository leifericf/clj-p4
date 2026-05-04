# Helix Core (p4d) for clj-p4 integration tests.
#
# Builds locally; image is not published. Pulls helix-p4d from Perforce's
# official Debian repo so we always exercise a current 2024.1+ server
# (including the -Mj JSON dispatch path).

FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        ca-certificates curl gnupg locales tzdata \
 && rm -rf /var/lib/apt/lists/*

# Install Perforce's GPG key and apt source, then helix-p4d + helix-cli.
RUN curl -fsSL https://package.perforce.com/perforce.pubkey \
        | gpg --dearmor -o /etc/apt/trusted.gpg.d/perforce.gpg \
 && echo 'deb http://package.perforce.com/apt/debian bookworm release' \
        > /etc/apt/sources.list.d/perforce.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends helix-p4d helix-cli \
 && rm -rf /var/lib/apt/lists/*

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

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
COPY seed.sh       /usr/local/bin/seed.sh
RUN chmod +x /usr/local/bin/entrypoint.sh /usr/local/bin/seed.sh

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["serve"]
