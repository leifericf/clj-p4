(ns clj-p4.schema
  "Malli schemas for clj-p4 domain records, public API options, and the
   wire-format coercion applied when parsing p4 output.

   Two consumers:
   - `clj-p4.api` — boundary validation of `clone!` / `sync!` options,
     with `malli.error/humanize`d messages on rejection.
   - `clj-p4.parse.semantic` — declarative coercion via `m/decode` and
     `record-transformer`, replacing manual `parse-long` /
     keyword-table conversion in the parser.

   Distinct from `clj-p4.spec`, which holds hand-rolled domain
   predicates (`depot-path?`, `parse-depot-path`); the two are
   complementary and intentionally not merged."
  (:require [clj-p4.spec :as spec]
            [malli.core :as m]
            [malli.transform :as mt]))

;; ---------------- Atomic value schemas ----------------

(def change
  "P4 changelist number — positive long. Wire format is a digit string;
   `mt/string-transformer` covers the decode."
  [:int {:min 1}])

(def epoch-ms
  "Epoch milliseconds. Wire format is an epoch-seconds digit string;
   the schema-local `:decode/string` multiplies by 1000."
  [:int {:decode/string
         {:enter (fn [v]
                   (cond
                     (number? v) v
                     (and (string? v) (re-matches #"-?\d+" v))
                     (* 1000 (parse-long v))
                     :else v))}}])

(def action
  "FileRev action enum. Wire format is a string token from the table in
   `parse-file-rev`; default keyword-decoding handles every value
   (including `move/add` / `move/delete` since `keyword` parses the
   namespace separator)."
  [:enum :add :edit :delete :branch :integrate :purge
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
  "P4 depot path, validated by the existing `clj-p4.spec/depot-path?`
   predicate to keep the regex grammar in one place."
  [:fn {:error/message "should be a P4 depot path (//depot/...)"}
   spec/depot-path?])

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
   [:rev/rev {:optional true} [:int {:min 0}]]
   [:rev/digest {:optional true} string?]
   [:rev/size {:optional true} [:int {:min 0}]]
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
   [:p4/server-version-major {:optional true} [:int {:min 1}]]
   [:p4/server-version-minor {:optional true} [:int {:min 0}]]])

;; ---------------- ConnectionSpec ----------------

(def user-map-entry
  [:map {:closed false}
   [:name {:optional true} string?]
   [:email {:optional true} string?]])

(def connection-spec
  "Per `clj-p4.spec/connection-spec?`, plus the retry/timeout knobs that
   live on the conn map but the older predicate didn't cover."
  [:map {:closed false}
   [:p4/port string?]
   [:p4/user {:optional true} [:maybe string?]]
   [:p4/client {:optional true} [:maybe string?]]
   [:p4/ticket {:optional true} [:maybe string?]]
   [:p4/charset {:optional true} keyword?]
   [:p4/timeout-ms {:optional true} pos-int?]
   [:p4/retries {:optional true} [:int {:min 0}]]
   [:p4/retry-backoff-ms {:optional true} pos-int?]])

;; ---------------- Public API options ----------------

(def ^:private common-fields
  "Fields shared by clone! and sync!. The schemas for clone-options /
   sync-options inline these so error messages point at the operation
   the caller actually invoked."
  [[:conn connection-spec]
   [:target [:or string? [:fn #(instance? java.io.File %)]]]
   [:ref {:optional true} string?]
   [:source {:optional true} string?]
   [:sources {:optional true} [:vector string?]]
   [:source->ref {:optional true} [:map-of string? string?]]
   [:max-changes {:optional true} pos-int?]
   [:exclude {:optional true} vector?]
   [:fetch-parallelism {:optional true} pos-int?]
   [:max-print-bytes {:optional true} pos-int?]
   [:user-map {:optional true} [:map-of string? user-map-entry]]
   [:emit-labels? {:optional true} boolean?]
   [:lookahead {:optional true} [:int {:min 0}]]
   [:no-merge? {:optional true} boolean?]
   [:progress-fn {:optional true} fn?]
   [:stop? {:optional true} fn?]])

(def clone-options
  "`clone!` options. The `:source` xor `:sources` constraint is enforced
   imperatively in `clone!` itself — malli expresses it awkwardly and
   the boundary throw already covers it with a clearer message."
  (into [:map {:closed false}] common-fields))

(def sync-options
  "`sync!` options — same shape as `clone-options` plus an optional
   `:since` change-number override."
  (into [:map {:closed false}]
        (conj common-fields
              [:since {:optional true} [:maybe pos-int?]])))

;; ---------------- Wire-format coercion transformer ----------------

(def record-transformer
  "Used by `clj-p4.parse.semantic` when decoding wire-format string
   values into typed library shapes. Composition of
   `mt/string-transformer` (string→long, string→keyword for enums) and
   the schema-local `:decode/string` properties defined above."
  mt/string-transformer)
