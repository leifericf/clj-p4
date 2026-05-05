(ns clj-p4.parse.depot-path
  "Depot-path canonicalisation. Perforce escapes `@`, `#`, `*`, and `%`
   in stored depot paths as `%40`, `%23`, `%2A`, and `%25`. `p4 -G fstat`
   and `p4 describe` return paths in this escaped form; this namespace
   decodes them back to their literal characters before they reach git
   fast-import.

   The depot side stays escaped — `p4 print //depot/foo%40bar.txt` is the
   canonical query form — so this decode is applied only at the local-path
   boundary in `clj-p4.execute`, never to `:rev/depot`."
  (:require [clojure.string :as str]))

(def ^:private escape-re
  #"%([0-9A-Fa-f]{2})")

(defn unescape
  "Decode `%XX` percent-escapes in a Perforce depot path back to literal
   characters. Sequences that aren't valid `%`+2-hex pass through
   verbatim, matching the p4 server's tolerance: `%foo` stays `%foo`, but
   `%40bar` becomes `@bar`. Idempotent on already-unescaped paths."
  [s]
  (when s
    (str/replace s escape-re
                 (fn [[_ hex]]
                   (str (char (Long/parseLong hex 16)))))))
