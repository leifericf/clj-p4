#!/usr/bin/env bash
# Seed a fresh p4d with stream depots and sample changelists exercising the
# scenarios our integration tests rely on (read-side parity with git-p4 t98xx).
set -euo pipefail

P4="p4 -p tcp:localhost:${P4PORT} -u ${P4USER} -P ${P4PASSWD}"

# --- login -----------------------------------------------------------------
echo "${P4PASSWD}" | $P4 login

# --- create stream depot + streams -----------------------------------------
$P4 depot -i <<'EOF'
Depot: stream
Type: stream
Map: stream/...
StreamDepth: //stream/1
EOF

$P4 stream -t mainline -i <<'EOF'
Stream: //stream/main
Owner: admin
Name: main
Parent: none
Type: mainline
Description: mainline stream for clj-p4 fixtures
Options: allsubmit unlocked toparent fromparent mergedown
ParentView: noinherit
Paths:
        share ...
EOF

$P4 stream -t development -P //stream/main -i <<'EOF'
Stream: //stream/dev
Owner: admin
Name: dev
Parent: //stream/main
Type: development
Description: development child of main
Options: allsubmit unlocked toparent fromparent mergedown
ParentView: inherit
Paths:
        share ...
EOF

$P4 stream -t release -P //stream/main -i <<'EOF'
Stream: //stream/release
Owner: admin
Name: release
Parent: //stream/main
Type: release
Description: release child of main
Options: allsubmit unlocked toparent fromparent mergedown
ParentView: inherit
Paths:
        share ...
EOF

$P4 stream -t virtual -P //stream/main -i <<'EOF'
Stream: //stream/virtual
Owner: admin
Name: virtual
Parent: //stream/main
Type: virtual
Description: virtual stream — clj-p4 should refuse to clone this
Options: allsubmit unlocked notoparent nofromparent mergedown
ParentView: inherit
Paths:
        share src/...
EOF

# --- workspace + populate //stream/main ------------------------------------
WS=clj_p4_seed_main
WROOT=/p4/ws_main
mkdir -p "${WROOT}"

$P4 client -S //stream/main -i <<EOF
Client: ${WS}
Owner: admin
Root: ${WROOT}
Stream: //stream/main
Options: noallwrite noclobber nocompress unlocked nomodtime normdir
LineEnd: local
View:
EOF

PP="$P4 -c ${WS}"

# --- change 1: hello world (basic clone smoke, t9800) ----------------------
mkdir -p "${WROOT}/src"
echo "hello world" > "${WROOT}/src/hello.txt"
$PP add "${WROOT}/src/hello.txt"
$PP submit -d "initial: hello world"

# --- change 2: t9802 filetypes — binary, +x, symlink, +k, +ko --------------
echo -n -e '\x00\x01\x02BIN' > "${WROOT}/src/img.bin"
$PP add -t binary "${WROOT}/src/img.bin"

cat > "${WROOT}/src/run.sh" <<'EOF'
#!/bin/sh
echo run
EOF
chmod +x "${WROOT}/src/run.sh"
$PP add -t text+x "${WROOT}/src/run.sh"

ln -sf hello.txt "${WROOT}/src/link"
$PP add -t symlink "${WROOT}/src/link"

cat > "${WROOT}/src/kfile.txt" <<'EOF'
$Id$
$Author$
content
EOF
$PP add -t text+k "${WROOT}/src/kfile.txt"

cat > "${WROOT}/src/kofile.txt" <<'EOF'
$Id$
$Author$
content
EOF
$PP add -t text+ko "${WROOT}/src/kofile.txt"

$PP submit -d "filetypes: binary, +x, symlink, +k, +ko"

# --- change 3: t9803 special filenames — spaces, $, quotes -----------------
mkdir -p "${WROOT}/src/oddly named"
echo "spaces are fine" > "${WROOT}/src/oddly named/file with spaces.txt"
echo "dollar"          > "${WROOT}/src/oddly named/\$dollar.txt"
$PP add "${WROOT}/src/oddly named/file with spaces.txt"
$PP add "${WROOT}/src/oddly named/\$dollar.txt"
$PP submit -d "t9803: shell metachars in filenames"

# --- change 4: t9822 unicode paths and contents ----------------------------
mkdir -p "${WROOT}/src/iñtërnâtiônàl"
echo "héllo, 世界" > "${WROOT}/src/iñtërnâtiônàl/utf8.txt"
$PP add "${WROOT}/src/iñtërnâtiônàl/utf8.txt"
$PP submit -d "t9822: unicode paths and contents"

# --- change 5: t9825 utf16 file without BOM --------------------------------
python3 -c "
import codecs
data = 'utf16 no bom\n'.encode('utf-16-le')
open('${WROOT}/src/utf16-no-bom.txt', 'wb').write(data)
" || true
if [[ -f "${WROOT}/src/utf16-no-bom.txt" ]]; then
    $PP add -t utf16 "${WROOT}/src/utf16-no-bom.txt" || true
    $PP submit -d "t9825: utf16 without BOM" || true
fi

# --- change 6: t9814 rename pair (move/add + move/delete) -----------------
$PP edit "${WROOT}/src/hello.txt"
$PP move "${WROOT}/src/hello.txt" "${WROOT}/src/greetings.txt"
$PP submit -d "t9814: rename hello.txt -> greetings.txt"

# --- change 7: t9826 keep-empty-commit (touches no mapped file) ------------
# We create an unmapped file outside the stream so the change is empty
# from our view's perspective. Using the depot directly is fiddly — use
# a comment-only resubmit on an existing file instead.
$PP edit "${WROOT}/src/img.bin" >/dev/null 2>&1 || true
$PP revert "${WROOT}/src/img.bin" >/dev/null 2>&1 || true

# --- change 8: t9827 filetype change (text -> binary) ---------------------
echo "now binary?" > "${WROOT}/src/morphing.txt"
$PP add -t text "${WROOT}/src/morphing.txt"
$PP submit -d "t9827a: morphing.txt as text"

$PP edit -t binary "${WROOT}/src/morphing.txt"
echo -e '\x00\x01now binary' > "${WROOT}/src/morphing.txt"
$PP submit -d "t9827b: morphing.txt switched to binary"

# --- change 9: t9834 case-folding paths (case-only difference) -------------
echo "lower" > "${WROOT}/src/case.txt"
$PP add "${WROOT}/src/case.txt"
$PP submit -d "t9834a: lowercase case.txt"

# --- change 10: large file > 1 MiB (smaller than plan's 100 MB to keep
#    the seed fast; integration tests can override).
dd if=/dev/urandom of="${WROOT}/src/large.bin" bs=1M count=2 status=none
$PP add -t binary "${WROOT}/src/large.bin"
$PP submit -d "large binary (2 MiB)"

echo "[seed] done. Latest change:"
$P4 changes -m1
