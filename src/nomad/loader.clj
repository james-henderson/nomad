(ns nomad.loader
  (:require [nomad.location :as l]
            [nomad.references :refer [resolve-references]]
            [nomad.merge :refer [deep-merge]]
            [nomad.references :as nr]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.tools.reader.edn :as edn]))

(defn try-slurp [slurpable]
  (try
    (slurp slurpable)

    (catch Exception e
      (log/warnf "Can't read config-file: '%s', ignoring..." slurpable)
      ::invalid-include)))

(defn parse-config [s]
  (when (string? s)
    (edn/read-string {:readers *data-readers*}
                     s)))

(defmulti config-source-etag type)

(defmethod config-source-etag java.io.File [file]
  (when (.exists file)
    (.lastModified file)))

(defmethod config-source-etag java.net.URL [url]
  (try
    (.getLastModified (.openConnection url))

    (catch Exception e
      e)))

(defn load-config-sources [initial-source {:keys [location]}]
  (loop [[config-source & more-sources :as config-sources] [initial-source]
         loaded-sources #{}
         configs []]
    (if (empty? config-sources)

      (with-meta configs
        {::etags (map (comp #(select-keys % [::etag ::config-source]) meta) configs)})

      (if-not config-source
        (recur more-sources loaded-sources configs)

        (let [new-config (some-> (try-slurp config-source)
                                 parse-config)]
          (recur (concat more-sources (some-> (:nomad/includes new-config)
                                              (resolve-references {:location location})
                                              (->> (remove #(contains? loaded-sources %))
                                                   (remove #{::invalid-include}))))

                 (conj loaded-sources config-source)
                 (conj configs (some-> new-config
                                       (vary-meta assoc
                                                  ::config-source config-source
                                                  ::etag (config-source-etag config-source))))))))))

(defn with-cached-config-if-unchanged [{:keys [config-source location cached-config]} merge-config]
  (let [{old-location ::location, old-initial-source ::initial-source, old-etags ::etags} (meta cached-config)]
    (if (and (= [old-location old-initial-source] [location config-source])
             (every? true? (for [{:keys [::config-source ::etag]} old-etags]
                             (= etag (config-source-etag config-source)))))

      (do
        cached-config)

      (let [new-sources (load-config-sources config-source {:location location})]
        (with-meta (merge-config new-sources)
          {::initial-source config-source
           ::location location
           ::etags (::etags (meta new-sources))})))))

(defn load-config [{:keys [config-source location cached-config] :as config-opts}]
  (with-cached-config-if-unchanged config-opts
    (fn merge-config [config-sources]
      (-> config-sources
          deep-merge
          (dissoc :nomad/includes)
          (nr/resolve-references {:location location})))))