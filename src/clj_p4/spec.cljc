(ns clj-p4.spec
  "Predicates and validators on the library's data shapes.

   Pure: no I/O, runs anywhere `.cljc` runs. The shapes themselves are documented
   in the project plan; this namespace is the authoritative gatekeeper."
  (:require [clojure.string :as str]))

(def ^:private depot-path-re
  #"//[A-Za-z0-9_][A-Za-z0-9_.\-]*(?:/[A-Za-z0-9_.\-]+|/\*|/\.\.\.)*")

(defn depot-path?
  "True if `s` looks like a Perforce depot path: starts with `//`, contains
   only safe characters, and has no path-traversal `..` segment."
  [s]
  (boolean
   (and (string? s)
        (re-matches depot-path-re s)
        (not (re-find #"(?:^|/)\.\.(?:/|$)" s)))))

(defn parse-depot-path
  "Parse `s` into a DepotPath map: `{:depot/raw :depot/depot :depot/segments
   :depot/wildcard}`. `:depot/wildcard` is `:ellipsis` for `/...`, `:star` if
   the final segment contains `*`, otherwise `nil`. Returns `nil` on invalid
   input."
  [s]
  (when (depot-path? s)
    (let [trimmed (subs s 2)
          parts   (str/split trimmed #"/")
          [depot & rest-parts] parts
          last-part (last parts)
          wildcard  (cond
                      (= "..." last-part)         :ellipsis
                      (and last-part
                           (str/includes? last-part "*")) :star
                      :else                       nil)
          segments  (vec (cond->> rest-parts
                           wildcard butlast))]
      {:depot/raw      s
       :depot/depot    depot
       :depot/segments segments
       :depot/wildcard wildcard})))

(defn validate-depot-path!
  "Throw `ex-info` if `s` is not a valid depot path; return `s` otherwise."
  [s]
  (or (and (depot-path? s) s)
      (throw (ex-info "Invalid Perforce depot path"
                      {:clj-p4/error :invalid-depot-path
                       :depot-path   s}))))

(defn connection-spec?
  "True if `m` looks like a ConnectionSpec: `:p4/port` is required and string;
   the optional fields, when present, have the right type."
  [m]
  (and (map? m)
       (string? (:p4/port m))
       (every? #(or (nil? (% m)) (string? (% m)))
               [:p4/user :p4/client :p4/ticket])
       (or (nil? (:p4/charset m))   (keyword? (:p4/charset m)))
       (or (nil? (:p4/timeout-ms m)) (and (number? (:p4/timeout-ms m))
                                          (pos?    (:p4/timeout-ms m))))))

(defn validate-connection-spec!
  "Throw `ex-info` if `m` is not a valid ConnectionSpec; return `m` otherwise."
  [m]
  (or (and (connection-spec? m) m)
      (throw (ex-info "Invalid ConnectionSpec"
                      {:clj-p4/error :invalid-connection-spec
                       :spec         m}))))
