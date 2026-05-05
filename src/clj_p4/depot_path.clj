(ns clj-p4.depot-path
  "Depot-path canonicalisation. Perforce escapes `@`, `#`, `*`, and `%`
   in stored depot paths as `%40`, `%23`, `%2A`, and `%25`. `p4 -G fstat`
   and `p4 describe` return paths in this escaped form; this namespace
   decodes them back to their literal characters before they reach git
   fast-import.

   The depot side stays escaped — `p4 print //depot/foo%40bar.txt` is the
   canonical query form — so this decode is applied only at the local-path
   boundary in `clj-p4.runner`, never to `:rev/depot`."
  (:require [clojure.string :as str]))

(def ^:private escape-re
  #"%([0-9A-Fa-f]{2})")

(defn- control-byte-index
  "Return the index of the first control character (0x00–0x1F or 0x7F) in
   `s`, or nil if none. Used to reject hostile / corrupted depot-path
   input at the unescape boundary."
  [^String s]
  (let [n (.length s)]
    (loop [i 0]
      (when (< i n)
        (let [c (int (.charAt s i))]
          (if (or (< c 0x20) (= c 0x7F))
            i
            (recur (inc i))))))))

(defn unescape
  "Decode `%XX` percent-escapes in a Perforce depot path back to literal
   characters. Sequences that aren't valid `%`+2-hex pass through
   verbatim, matching the p4 server's tolerance: `%foo` stays `%foo`, but
   `%40bar` becomes `@bar`. Idempotent on already-unescaped paths.

   If the decoded output would contain a control character (`< 0x20` or
   `0x7F`) — e.g. from `%00`, `%1F`, `%7F` — throws
   `:clj-p4/error :invalid-depot-path-byte`. P4 paths legitimately never
   contain control bytes; rejecting them here closes a corruption door
   against malicious or corrupted server data before the path reaches
   `git fast-import`, whose stream format treats NULs and bare control
   bytes as garbage."
  [s]
  (when s
    (let [decoded (str/replace s escape-re
                               (fn [[_ hex]]
                                 (str (char (Long/parseLong hex 16)))))]
      (if-let [i (control-byte-index decoded)]
        (throw (ex-info (str "depot-path/unescape: control byte at position "
                             i " of decoded path " (pr-str s))
                        {:clj-p4/error :invalid-depot-path-byte
                         :path         s
                         :decoded      decoded
                         :byte         (int (.charAt ^String decoded i))
                         :index        i}))
        decoded))))
