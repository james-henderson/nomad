(ns nomad
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [nomad.map :refer [deep-merge]]))

(def ^:private get-hostname
  (memoize
   (fn []
     (.trim (:out (sh "hostname"))))))

(def ^:private get-instance
  (memoize
   (fn []
     (get (System/getenv) "NOMAD_INSTANCE" :default))))

(defprotocol ConfigFile
  (etag [_])
  (slurp* [_]))

(extend-protocol ConfigFile
  java.io.File
  (etag [f] {:file f
             :last-mod (.lastModified f)})
  (slurp* [f] (slurp f))

  java.net.URL
  (etag [url]
    (case (.getProtocol url)
      "file" (etag (io/as-file url))

      ;; otherwise, we presume the config file is read-only
      ;; (i.e. in a JAR file)
      {:url url}))

  (slurp* [url] (slurp url))

  nil
  (etag [_] nil)
  (slurp* [_] (pr-str {})))

(defn- nomad-data-readers [snippet-reader]
  (assoc *data-readers*
    'nomad/file io/file
    'nomad/snippet snippet-reader))

(defn- reload-config-file [config-file]
  (let [config-str (slurp* config-file)
        without-snippets (binding [*data-readers* (nomad-data-readers
                                                   (constantly ::snippet))]
                           (read-string config-str))
        snippets (get without-snippets :nomad/snippets)
        with-snippets (binding [*data-readers* (nomad-data-readers
                                                (fn [ks]
                                                  (get-in snippets ks)))]
                        (dissoc (read-string config-str) :nomad/snippets))]
    {:etag (etag config-file)
     :config-file config-file
     :config with-snippets}))

(defn- update-config-file [current-config config-file]
  (let [{old-etag :etag} current-config
        new-etag (etag config-file)]
    (if (not= old-etag new-etag)
      (reload-config-file config-file)
      current-config)))

(defn- update-specific-config [current-config downstream-key upstream-key selector value]
  (let [{new-etag :etag
         new-upstream-config :config} (get current-config upstream-key)
         
         {old-etag :upstream-etag
          :as current-downstream-config} (get current-config downstream-key)]

    (assoc current-config
      downstream-key (if (= new-etag old-etag)
                       current-downstream-config
                       {:upstream-etag new-etag
                        :etag new-etag
                        :config (get-in new-upstream-config [selector value])}))))

(defn- add-environment [configs]
  (assoc configs
    :environment {:nomad/hostname (get-hostname)
                  :nomad/instance (get-instance)}))

(defn- update-private-config [configs src-key dest-key]
  (let [{old-public-etag :public-etag
         old-etag :etag
         :as current-config} (get configs dest-key)

         {new-public-etag :etag} (get configs src-key)

         private-file (get-in configs [src-key :config :nomad/private-file])]
    
    (assoc configs
      dest-key (if (not= old-public-etag new-public-etag)
                 (reload-config-file private-file)
                 (update-config-file current-config private-file)))))

(defn- merge-configs [configs]
  (-> (deep-merge (or (get-in configs [:general :config]) {})
                  (or (get-in configs [:general-private :config]) {})
                  (or (get-in configs [:host :config]) {})
                  (or (get-in configs [:host-private :config]) {})
                  (or (get-in configs [:instance :config]) {})
                  (or (get-in configs [:instance-private :config]) {})
                  (or (get-in configs [:environment]) {}))
      (dissoc :nomad/hosts :nomad/instances :nomad/private-file)
      (with-meta configs)))

(defn- update-config [current-config]
  (-> current-config
      (update-in [:general] update-config-file (get-in current-config [:general :config-file]))
      (update-private-config :general :general-private)
      (update-specific-config :host :general :nomad/hosts (get-hostname))
      (update-specific-config :instance :host :nomad/instances (get-instance))
      add-environment
      (update-private-config :host :host-private)
      (update-private-config :instance :instance-private)))

(defn- get-current-config [config-ref]
  (dosync
   (-> (alter config-ref update-config)
       merge-configs)))

(defmacro defconfig [name file-or-resource]
  `(let [config-ref# (ref {:general {:config-file ~file-or-resource}})]
     (defn ~name []
       (#'get-current-config config-ref#))))
