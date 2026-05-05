(ns clj-p4.schemas
  "Malli schemas for clj-p4 domain records, public API options, and the
   wire-format coercion applied when parsing p4 output.

   Two consumers:
   - `clj-p4.api` — boundary validation of `clone!` / `fetch!` options,
     with `malli.error/humanize`d messages on rejection.
   - `clj-p4.records` — declarative coercion via `m/decode` and
     `record-transformer`, replacing manual `parse-long` /
     keyword-table conversion in the parser.

   Distinct from `clj-p4.predicates`, which holds hand-rolled domain
   predicates (`depot-path?`, `parse-depot-path`); the two are
   complementary and intentionally not merged."
  (:require [clj-p4.predicates :as predicates]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.transform :as mt]))

;; ---------------- Atomic value schemas ----------------

(defn- decode-int-or-nil
  "Coerce a wire value to a long. Numbers and nil pass through; strings
   go through `parse-long`, which yields nil for unparseable input.
   This preserves the pre-V3 `(when (parse-long s))` semantics —
   downstream code relies on bad numeric wire values surfacing as nil
   rather than as the raw string. Anything else (rare) passes through
   unchanged so the schema's predicate can reject it."
  [v]
  (cond
    (number? v) v
    (string? v) (parse-long v)
    :else       v))

(def ^:private safe-long
  "Long-typed schema with a nil-safe wire decoder. Use anywhere a
   p4 wire value lands as a string of digits."
  [:int {:decode/string {:enter decode-int-or-nil}}])

(def change
  "P4 changelist number — positive long, decoded from a digit string."
  [:int {:min 1 :decode/string {:enter decode-int-or-nil}}])

(def epoch-ms
  "Epoch milliseconds. Wire format is an epoch-seconds digit string;
   the decoder multiplies by 1000 and returns nil for unparseable
   input."
  [:int {:decode/string
         {:enter (fn [v]
                   (cond
                     (number? v) v
                     (string? v) (when-let [n (parse-long v)]
                                   (* 1000 n))
                     :else v))}}])

(def ^:private action-wire->kw
  "Closed lookup table from p4 wire action token to its keyword.
   Unknown wire tokens decode to nil so the parser surfaces invariant
   breaks instead of smuggling unrecognised actions through as fresh
   keywords."
  {"add"         :add
   "edit"        :edit
   "delete"      :delete
   "branch"      :branch
   "integrate"   :integrate
   "purge"       :purge
   "move/add"    :move/add
   "move/delete" :move/delete})

(def action
  "FileRev action enum. Wire format is a string token from the closed
   `action-wire->kw` table; unknown tokens decode to nil rather than a
   fresh keyword, preserving the pre-V3 observability property."
  [:enum {:decode/string {:enter (fn [v]
                                   (cond
                                     (keyword? v) v
                                     (string? v)  (action-wire->kw v)
                                     :else        v))}}
   :add :edit :delete :branch :integrate :purge
   :move/add :move/delete])

(def status
  "Changelist status enum."
  [:enum :pending :submitted :shelved :new])

(def stream-type
  "Source type. `:classic` covers non-stream depot paths synthesised by
   `clj-p4.api/resolve-source` when `p4 stream -o` reports the path is
   not a stream."
  [:enum :mainline :development :release :virtual :task
         :sparsedev :sparserel :classic])

(def path-kind
  "Stream `Paths:` directive kind."
  [:enum :share :isolate :import :import+ :exclude])

(def depot-path
  "P4 depot path, validated by the existing `clj-p4.predicates/depot-path?`
   predicate to keep the regex grammar in one place."
  [:fn {:error/message "should be a P4 depot path (//depot/...)"}
   predicates/depot-path?])

(def file-coercible
  "Anything `clojure.java.io/file` can handle once stringified — the
   implementation in `clj-p4.api` calls `(io/file (str target))`, which
   accepts `String`, `java.io.File`, `java.nio.file.Path`, `java.net.URI`,
   `java.net.URL`, and any other object whose `str` produces a non-blank
   path. Rejects nil and the empty / blank string so callers get a clear
   boundary error instead of a deeper NPE."
  [:fn {:error/message
        "should be a non-blank file path (string, File, Path, URI, …)"}
   (fn [v] (and (some? v)
                (not (str/blank? (str v)))))])

(def unicode-flag
  "P4 `unicode` info field. Wire format is the literal string
   `\"enabled\"` (or absent); decoder maps to a boolean."
  [:boolean
   {:decode/string {:enter (fn [v]
                             (cond
                               (boolean? v) v
                               :else (= "enabled" v)))}}])

;; ---------------- Stream-paths sub-schema ----------------

(def path-line
  "One `Paths:` directive — `[:share \"src/...\"]` or
   `[:import \"//depot/lib/...\" \"lib/...\"]`."
  [:cat path-kind string? [:* string?]])

;; ---------------- File-rev ----------------

(def file-rev
  "One entry in `:p4/files`. Open map (`:closed false`) so the parser
   can attach `:rev/type`, `:rev/flags`, `:rev/keyword-flags` from
   `parse-file-type` without listing every flag combination here."
  [:map {:closed false}
   [:rev/depot string?]
   [:rev/action {:optional true} action]
   [:rev/rev {:optional true} [:maybe safe-long]]
   [:rev/digest {:optional true} string?]
   [:rev/size {:optional true} [:maybe safe-long]]
   [:rev/moved-file {:optional true} string?]
   [:rev/type {:optional true} keyword?]
   [:rev/flags {:optional true} [:set keyword?]]
   [:rev/keyword-flags {:optional true} [:set keyword?]]])

;; ---------------- Changelist record ----------------

(def changelist-record
  "Output of `parse-changelist` / `parse-describe`. Several fields can
   be nil when the originating `p4 describe` shape omits them — the
   parser reflects p4's own variability rather than imposing a
   required-everywhere shape."
  [:map {:closed false}
   [:p4/change [:maybe change]]
   [:p4/user [:maybe string?]]
   [:p4/client [:maybe string?]]
   [:p4/desc [:maybe string?]]
   [:p4/status [:maybe status]]
   [:p4/stream {:optional true} string?]
   [:p4/time {:optional true} epoch-ms]
   [:p4/files {:optional true} [:vector file-rev]]])

;; ---------------- Stream spec ----------------

(def stream-spec
  "Output of `parse-stream-spec`."
  [:map {:closed false}
   [:stream/name string?]
   [:stream/parent [:maybe string?]]
   [:stream/type [:maybe stream-type]]
   [:stream/paths [:vector vector?]]
   [:stream/remapped [:vector [:tuple string? string?]]]
   [:stream/ignored [:vector string?]]
   [:stream/options [:set keyword?]]
   [:stream/updated {:optional true} string?]])

;; ---------------- Server info ----------------

(def server-info
  "Output of `parse-info`."
  [:map {:closed false}
   [:p4/server-version [:maybe string?]]
   [:p4/server-uptime [:maybe string?]]
   [:p4/server-address [:maybe string?]]
   [:p4/case-handling [:maybe keyword?]]
   [:p4/unicode? {:optional true} unicode-flag]
   [:p4/server-version-major {:optional true} [:maybe safe-long]]
   [:p4/server-version-minor {:optional true} [:maybe safe-long]]])

;; ---------------- ConnectionSpec ----------------

(def user-map-entry
  [:map {:closed false}
   [:name {:optional true} [:maybe string?]]
   [:email {:optional true} [:maybe string?]]])

(def ^:private positive-number
  "Any positive number — matches the older `clj-p4.predicates/connection-spec?`
   contract, which accepts doubles too (JSON sources often produce
   them). Use this where a timeout / backoff is expressed in ms."
  [:and number? pos?])

(def connection-spec
  "Per `clj-p4.predicates/connection-spec?`, plus the retry/timeout knobs that
   live on the conn map but the older predicate didn't cover."
  [:map {:closed false}
   [:p4/port string?]
   [:p4/user {:optional true} [:maybe string?]]
   [:p4/client {:optional true} [:maybe string?]]
   [:p4/ticket {:optional true} [:maybe string?]]
   [:p4/charset {:optional true} keyword?]
   [:p4/timeout-ms {:optional true} positive-number]
   [:p4/retries {:optional true} [:int {:min 0}]]
   [:p4/retry-backoff-ms {:optional true} positive-number]])

;; ---------------- Public API options ----------------

(def ^:private common-fields
  "Fields shared by clone! and fetch!. The schemas for clone-options /
   fetch-options inline these so error messages point at the operation
   the caller actually invoked."
  [[:conn connection-spec]
   [:target file-coercible]
   [:ref {:optional true} string?]
   [:source {:optional true} string?]
   [:sources {:optional true} [:sequential string?]]
   [:source->ref {:optional true} [:map-of string? string?]]
   [:max-changes {:optional true} [:maybe pos-int?]]
   [:exclude {:optional true} [:maybe vector?]]
   [:exclude-binaries? {:optional true} [:maybe boolean?]]
   [:exclude-categories {:optional true}
    [:maybe [:or [:= :all] [:set keyword?]]]]
   [:extra-excludes {:optional true} [:maybe [:sequential string?]]]
   [:includes {:optional true} [:maybe [:sequential string?]]]
   [:fetch-parallelism {:optional true} [:maybe pos-int?]]
   [:max-print-bytes {:optional true} [:maybe pos-int?]]
   [:user-map {:optional true} [:maybe [:map-of string? user-map-entry]]]
   [:emit-labels? {:optional true} [:maybe boolean?]]
   [:lookahead {:optional true} [:maybe [:int {:min 0}]]]
   [:no-merge? {:optional true} [:maybe boolean?]]
   [:keep-empty-commits? {:optional true} [:maybe boolean?]]
   [:changes-block-size {:optional true} [:maybe pos-int?]]
   [:metadata-decoding-strategy {:optional true}
    [:enum :strict :fallback :passthrough]]
   [:metadata-fallback-encoding {:optional true} [:maybe string?]]
   [:progress-fn {:optional true} ifn?]
   [:stop? {:optional true} ifn?]])

(def clone-options
  "`clone!` options. The `:source` xor `:sources` constraint is enforced
   imperatively in `clone!` itself — malli expresses it awkwardly and
   the boundary throw already covers it with a clearer message."
  (into [:map {:closed false}] common-fields))

(def fetch-options
  "`fetch!` options — same shape as `clone-options` plus an optional
   `:since` change-number override."
  (into [:map {:closed false}]
        (conj common-fields
              [:since {:optional true} [:maybe pos-int?]])))

;; ---------------- Wire-format coercion transformer ----------------

(def record-transformer
  "Used by `clj-p4.records` when decoding wire-format string
   values into typed library shapes. Composition of
   `mt/string-transformer` (string→long, string→keyword for enums) and
   the schema-local `:decode/string` properties defined above."
  mt/string-transformer)
