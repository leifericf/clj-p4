(ns clj-p4.parse.semantic
  "Generic record-maps → typed library shapes (`StreamSpec`, `ChangelistRecord`,
   `FileRev`, server-info).

   Input: a string-keyed map as decoded by `clj-p4.parse.marshal` from
   `p4 -G` output. Indexed multi-valued fields appear as separate keys
   (e.g. `\"Paths0\"`, `\"Paths1\"`); helpers here coalesce them.

   Output: namespaced-keyword maps documented in the project plan.

   Times: epoch-milliseconds (long), to keep the layer host-neutral.
   `:stream/updated` stays as the raw `\"YYYY/MM/DD HH:MM:SS\"` string —
   it is metadata only, not used in clone/sync logic.

   Value-level coercion (string → long, string → keyword, epoch-seconds
   → epoch-ms, `\"enabled\"` → boolean) is delegated to
   `clj-p4.schema/record-transformer` via `malli.core/decode`. Helpers
   in this namespace handle the *structural* work malli can't express:
   coalescing indexed fields, splitting `Paths:` lines into typed
   tuples, and decomposing the `text+kx` file-type string into
   `:rev/type` / `:rev/flags` / `:rev/keyword-flags`."
  (:require [clj-p4.schema :as schema]
            [clojure.string :as str]
            [malli.core :as m]))

(defn- regex-escape [s]
  (str/replace s #"[.\\+*?\[\]\^\$\(\)\{\}\|]" #(str "\\" %)))

(defn- indexed-values
  "Coalesce keys matching `<prefix><n>` (n = 0, 1, …) into a vector in order.
   Falls back to a single-value key `<prefix>`'s value if present."
  [m prefix]
  (or (when-let [single (get m prefix)] [single])
      (->> m
           keys
           (keep (fn [k]
                   (when-let [[_ n] (re-matches
                                     (re-pattern (str (regex-escape prefix) "(\\d+)"))
                                     (str k))]
                     [(parse-long n) (get m k)])))
           (sort-by first)
           (mapv second))))

(def ^:private path-kind->kw
  {"share"   :share
   "isolate" :isolate
   "import"  :import
   "import+" :import+
   "exclude" :exclude})

(defn- parse-path-line
  "`\"share src/...\"` → `[:share \"src/...\"]`.
   `\"import //depot/lib/... lib/...\"` → `[:import \"//depot/lib/...\" \"lib/...\"]`."
  [line]
  (let [trimmed (str/trim (or line ""))
        toks    (str/split trimmed #"\s+" 3)
        kind    (path-kind->kw (first toks))]
    (when (and kind (seq (rest toks)))
      (into [kind] (rest toks)))))

(defn- parse-remap-line [line]
  (let [[a b] (str/split (str/trim (or line "")) #"\s+" 2)]
    (when (and a b) [a b])))

(defn parse-stream-spec
  "Generic record from `p4 stream -o //stream/x` → `StreamSpec`. Value
   coercion (`Type:` string → keyword) is performed by
   `schema/record-transformer`; this fn handles the structural work."
  [record]
  (let [opts-str (get record "Options")]
    (m/decode
     schema/stream-spec
     (cond-> {:stream/name    (get record "Stream")
              :stream/parent  (get record "Parent")
              :stream/type    (get record "Type")
              :stream/paths   (->> (indexed-values record "Paths")
                                   (keep parse-path-line)
                                   vec)
              :stream/remapped (->> (indexed-values record "Remapped")
                                    (keep parse-remap-line)
                                    vec)
              :stream/ignored  (->> (indexed-values record "Ignored")
                                    (mapv str/trim))
              :stream/options  (if (str/blank? opts-str)
                                 #{}
                                 (->> (str/split (str/trim opts-str) #"\s+")
                                      (map keyword)
                                      set))}
       (get record "Update")
       (assoc :stream/updated (get record "Update")))
     schema/record-transformer)))

(def ^:private base-type->kw
  {"text"     :text
   "binary"   :binary
   "utf16"    :utf16
   "utf8"     :utf8
   "symlink"  :symlink
   "apple"    :apple
   "resource" :resource
   "unicode"  :unicode})

(defn- parse-flags
  "`\"+kx\"` or `\"+ko\"` → `#{:k :x}` / `#{:ko}`.
   Recognises `ko` as a single token; everything else single-char.
   `Sn` (numbered) tokens preserved as `:S<n>`."
  [mod-str]
  (loop [i 0, acc #{}]
    (if (>= i (count mod-str))
      acc
      (let [pair (when (< (inc i) (count mod-str))
                   (subs mod-str i (+ i 2)))
            ch   (subs mod-str i (inc i))]
        (cond
          (= "ko" pair)
          (recur (+ i 2) (conj acc :ko))

          (and (= "S" ch)
               (re-matches #"\d" (or (subs mod-str (inc i) (min (count mod-str) (+ i 2))) "")))
          (let [j (loop [j (inc i)]
                    (if (and (< j (count mod-str))
                             (re-matches #"\d" (subs mod-str j (inc j))))
                      (recur (inc j))
                      j))]
            (recur j (conj acc (keyword (subs mod-str i j)))))

          :else
          (recur (inc i) (conj acc (keyword ch))))))))

(defn parse-file-type
  "`\"text+kx\"` → `{:rev/type :text :rev/flags #{:k :x} :rev/keyword-flags #{:k}}`."
  [s]
  (let [[base mods] (str/split (or s "") #"\+" 2)
        flags        (if (str/blank? mods) #{} (parse-flags mods))
        kw-flags     (cond
                       (contains? flags :ko) #{:ko}
                       (contains? flags :k)  #{:k}
                       :else                  #{})]
    {:rev/type           (or (base-type->kw base) :text)
     :rev/flags          flags
     :rev/keyword-flags  kw-flags}))

(defn- parse-file-rev
  "Indexed file fields → FileRev. `idx` is the suffix used in describe
   records. Value coercion (string→keyword for `:rev/action`,
   string→long for `:rev/rev` / `:rev/size`) is delegated to
   `schema/record-transformer`."
  [record idx]
  (let [k      #(get record (str % idx))
        type-m (parse-file-type (k "type"))]
    (m/decode
     schema/file-rev
     (cond-> (merge type-m
                    {:rev/depot  (k "depotFile")
                     :rev/action (k "action")})
       (k "rev")       (assoc :rev/rev (k "rev"))
       (k "digest")    (assoc :rev/digest (k "digest"))
       (k "fileSize")  (assoc :rev/size (k "fileSize"))
       (k "movedFile") (assoc :rev/moved-file (k "movedFile")))
     schema/record-transformer)))

(defn- file-indices
  "Indices `n` for which `depotFile<n>` exists in `record`, sorted."
  [record]
  (->> (keys record)
       (keep (fn [k]
               (when-let [[_ n] (re-matches #"depotFile(\d+)" (str k))]
                 (parse-long n))))
       sort))

(defn parse-changelist
  "Generic record (from `p4 changes -l -G` or similar) → ChangelistRecord
   *without* `:p4/files` (use `parse-describe` for that). Value coercion
   is delegated to `schema/record-transformer`."
  [record]
  (m/decode
   schema/changelist-record
   (cond-> {:p4/change (get record "change")
            :p4/user   (get record "user")
            :p4/client (get record "client")
            :p4/desc   (get record "desc")
            :p4/status (get record "status")}
     (get record "stream") (assoc :p4/stream (get record "stream"))
     (get record "time")   (assoc :p4/time (get record "time")))
   schema/record-transformer))

(defn parse-describe
  "Generic record from `p4 describe -s -G <change>` → ChangelistRecord with
   `:p4/files` populated."
  [record]
  (assoc (parse-changelist record)
         :p4/files (mapv #(parse-file-rev record %) (file-indices record))))

(defn parse-files-list
  "Sequence of generic records from `p4 files -G //...` → seq of FileRev.
   The records here are flat (no `<field><n>` numbering)."
  [records]
  (mapv (fn [r]
          (let [type-m (parse-file-type (get r "type"))]
            (m/decode
             schema/file-rev
             (cond-> (merge type-m
                            {:rev/depot  (get r "depotFile")
                             :rev/action (get r "action")})
               (get r "rev")      (assoc :rev/rev    (get r "rev"))
               (get r "digest")   (assoc :rev/digest (get r "digest"))
               (get r "fileSize") (assoc :rev/size   (get r "fileSize")))
             schema/record-transformer)))
        records))

(defn parse-info
  "Generic record from `p4 info -G` → server info map. Exposes
   `:p4/server-version-major` / `-minor` for capability detection
   (e.g. `-Mj` JSON support requires major ≥ 2024 minor ≥ 1). Version
   regex extraction stays here; everything else is `schema`-driven
   coercion."
  [record]
  (let [version (get record "serverVersion")
        [_ major minor] (when version
                          (re-find #"(\d{4})\.(\d+)" version))]
    (m/decode
     schema/server-info
     (cond-> {:p4/server-version  version
              :p4/server-uptime   (get record "serverUptime")
              :p4/server-address  (get record "serverAddress")
              :p4/case-handling   (get record "caseHandling")
              :p4/unicode?        (get record "unicode")}
       major (assoc :p4/server-version-major major)
       minor (assoc :p4/server-version-minor minor))
     schema/record-transformer)))
