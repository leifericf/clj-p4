(ns clj-p4.parse.semantic
  "Generic record-maps → typed library shapes (`StreamSpec`, `ChangelistRecord`,
   `FileRev`, server-info).

   Input: a string-keyed map as decoded by `clj-p4.parse.marshal` from
   `p4 -G` output. Indexed multi-valued fields appear as separate keys
   (e.g. `\"Paths0\"`, `\"Paths1\"`); helpers here coalesce them.

   Output: namespaced-keyword maps documented in the project plan.

   Times: epoch-milliseconds (long), to keep the layer host-neutral.
   `:stream/updated` stays as the raw `\"YYYY/MM/DD HH:MM:SS\"` string —
   it is metadata only, not used in clone/sync logic."
  (:require [clojure.string :as str]))

(defn- regex-escape [s]
  (str/replace s #"[.\\+*?\[\]\^\$\(\)\{\}\|]" "\\\\$0"))

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

(def ^:private stream-type->kw
  {"mainline"    :mainline
   "development" :development
   "release"     :release
   "virtual"     :virtual
   "task"        :task})

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
  "Generic record from `p4 stream -o //stream/x` → `StreamSpec`."
  [record]
  (let [type-str (get record "Type")
        opts-str (get record "Options")]
    (cond-> {:stream/name    (get record "Stream")
             :stream/parent  (get record "Parent")
             :stream/type    (stream-type->kw type-str)
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
      (assoc :stream/updated (get record "Update")))))

(def ^:private action->kw
  {"add"          :add
   "edit"         :edit
   "delete"       :delete
   "branch"       :branch
   "integrate"    :integrate
   "purge"        :purge
   "move/add"     :move/add
   "move/delete"  :move/delete})

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
  "Indexed file fields → FileRev. `idx` is the suffix used in describe records."
  [record idx]
  (let [k       #(get record (str % idx))
        type-m  (parse-file-type (k "type"))
        size-s  (k "fileSize")
        rev-s   (k "rev")]
    (cond-> (merge type-m
                   {:rev/depot  (k "depotFile")
                    :rev/action (action->kw (k "action"))})
      rev-s   (assoc :rev/rev    (parse-long rev-s))
      (k "digest") (assoc :rev/digest (k "digest"))
      size-s  (assoc :rev/size   (parse-long size-s)))))

(defn- file-indices
  "Indices `n` for which `depotFile<n>` exists in `record`, sorted."
  [record]
  (->> (keys record)
       (keep (fn [k]
               (when-let [[_ n] (re-matches #"depotFile(\d+)" (str k))]
                 (parse-long n))))
       sort))

(defn- epoch-seconds-str->ms [s]
  (when-let [n (and s (parse-long s))]
    (* 1000 n)))

(defn parse-changelist
  "Generic record (from `p4 changes -l -G` or similar) → ChangelistRecord
   *without* `:p4/files` (use `parse-describe` for that)."
  [record]
  (let [change-s (get record "change")]
    (cond-> {:p4/change (when change-s (parse-long change-s))
             :p4/user   (get record "user")
             :p4/client (get record "client")
             :p4/desc   (get record "desc")
             :p4/status (some-> (get record "status") keyword)}
      (get record "stream") (assoc :p4/stream (get record "stream"))
      (get record "time")   (assoc :p4/time   (epoch-seconds-str->ms (get record "time"))))))

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
            (cond-> (merge type-m
                           {:rev/depot  (get r "depotFile")
                            :rev/action (some-> (get r "action") action->kw)})
              (get r "rev")      (assoc :rev/rev    (parse-long (get r "rev")))
              (get r "digest")   (assoc :rev/digest (get r "digest"))
              (get r "fileSize") (assoc :rev/size   (parse-long (get r "fileSize"))))))
        records))

(defn parse-info
  "Generic record from `p4 info -G` → server info map. Exposes
   `:p4/server-version-major` / `-minor` for capability detection
   (e.g. `-Mj` JSON support requires major ≥ 2024 minor ≥ 1)."
  [record]
  (let [version (get record "serverVersion")
        [_ major minor] (when version
                          (re-find #"(\d{4})\.(\d+)" version))]
    (cond-> {:p4/server-version    version
             :p4/server-uptime     (get record "serverUptime")
             :p4/server-address    (get record "serverAddress")
             :p4/case-handling     (some-> (get record "caseHandling") keyword)
             :p4/unicode?          (= "enabled" (get record "unicode"))}
      major (assoc :p4/server-version-major (parse-long major))
      minor (assoc :p4/server-version-minor (parse-long minor)))))
