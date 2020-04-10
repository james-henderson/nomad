(ns nomad.config
  (:require [buddy.core.codecs :as bc]
            [buddy.core.codecs.base64 :as b64]
            [buddy.core.crypto :as b]
            [buddy.core.nonce :as bn]
            [clojure.set :as set]
            [clojure.tools.reader.edn :as edn]
            [clojure.string :as s]
            [clojure.walk :as w]))

(def ^:dynamic *opts* nil)
(def !clients (atom #{}))

(defn generate-key []
  (bc/bytes->str (b64/encode (bn/random-bytes 32))))

(def ^:private block-size
  (b/block-size (b/block-cipher :aes :cbc)))

(defn- resolve-secret-key [secret-key]
  (cond
    (string? secret-key) secret-key
    (keyword? secret-key) (or (get (:secret-keys *opts*) secret-key)
                              (throw (ex-info "missing secret-key" {"secret-key" secret-key})))
    :else (throw (ex-info "invalid secret-key" {:secret-key secret-key}))))

(defn encrypt [secret-key plain-obj]
  (let [iv (bn/random-bytes block-size)]
    (->> [iv (-> (pr-str plain-obj)
                 bc/str->bytes
                 (b/encrypt (b64/decode (bc/str->bytes (resolve-secret-key secret-key))) iv))]
         (mapcat seq)
         byte-array
         b64/encode
         bc/bytes->str)))

(defn decrypt [secret-key cipher-text]
  (let [[iv cipher-bytes] (map byte-array (split-at block-size (b64/decode cipher-text)))]
    (-> cipher-bytes
        (b/decrypt (b64/decode (bc/str->bytes (resolve-secret-key secret-key))) iv)
        edn/read-string)))

(defmacro switch* {:style/indent 1} [opts & clauses]
  (let [switches-sym (gensym 'switches)]
    `(let [~switches-sym (set (:switches ~opts))]
       (cond
         ~@(for [[clause expr] (partition 2 clauses)
                 form [`(some ~switches-sym ~(cond
                                               (set? clause) clause
                                               (keyword? clause) #{clause}))
                       expr]]
             form)
         :else ~(when (pos? (mod (count clauses) 2))
                  (last clauses))))))

(defmacro switch
  "Takes a set of switch/expr clauses, and an optional default value.
  Returns the configuration from the first active switch, or the default if none are active, or nil.

  (n/switch
    <switch> <expr>
    <switch-2> <expr-2>
    ...
    <default-expr>)"
  {:style/indent 0}
  [& clauses]
  `(switch* *opts* ~@clauses))

(defn add-client! [var]
  (let [{var-ns :ns, var-name :name} (meta var)]
    (swap! !clients conj (symbol (str var-ns) (name var-name)))))

(defmacro defconfig [sym config]
  (let [config-sym (gensym 'config)]
    `(let [~config-sym (-> (fn [opts#]
                             (binding [*opts* opts#]
                               ~config))
                           memoize)]
       (doto (def ~(-> sym (with-meta {:dynamic true}))
               (when *opts*
                 (~config-sym *opts*)))
         (alter-meta! assoc :nomad/config ~config-sym)
         add-client!))))

(defn parse-switches [switches]
  (some-> switches
          (s/split #",")
          (->> (into #{} (map keyword)))))

(def env-switches
  (->> [(System/getenv "NOMAD_SWITCHES")
        (System/getProperty "nomad.switches")]
       (into #{} (mapcat parse-switches))))

(defn eval-config [config-var {:keys [switches secret-keys override-switches]}]
  ((:nomad/config (meta config-var)) {:switches (get override-switches config-var switches), :secret-keys secret-keys}))

(defn set-defaults! [{:keys [switches secret-keys override-switches], :as defaults}]
  (alter-var-root #'*opts* merge defaults)
  (doseq [client @!clients]
    (when-let [config-var (resolve client)]
      (alter-var-root config-var (constantly (eval-config config-var *opts*))))))

(defn with-config-override* [{:keys [switches secret-keys override-switches] :as opts-override} f]
  (let [opts-override (merge *opts* opts-override)]
    (binding [*opts* opts-override]
      (with-bindings (into {}
                           (keep (fn [client]
                                   (when-let [config-var (resolve client)]
                                     [config-var (eval-config config-var opts-override)])))
                           @!clients)
        (f)))))

(doto (defmacro with-config-override [opts & body]
        `(with-config-override* ~opts (fn [] ~@body)))

  (alter-meta! assoc :arglists '([{:keys [switches secret-keys override-switches] :as opts-override} & body])))
