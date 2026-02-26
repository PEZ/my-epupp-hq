(ns sync
  (:require [babashka.nrepl-client :as nrepl]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; --- Core nREPL Communication ---

(defn- parse-promise-str
  "Parses '#<Promise VALUE>' relay response format. Returns inner EDN value."
  [s]
  (when (and (string? s) (str/starts-with? s "#<Promise "))
    (edn/read-string (subs s 10 (dec (count s))))))

(defn- connection-refused? [e]
  (or (instance? java.net.ConnectException e)
      (and (instance? java.util.concurrent.ExecutionException e)
           (instance? java.net.ConnectException (.getCause e)))))

(defn- wrap-promise-expr [expr]
  (str "(-> (try (-> " expr
       " (.then (fn [v] {:ok v}))"
       " (.catch (fn [e] {:error (.-message e)})))"
       " (catch :default e (js/Promise.resolve {:error (.-message e)}))))"))

(defn- eval-epupp
  [{:keys [port expr timeout-ms]
    :or {timeout-ms 5000}}]
  (try
    (let [f (future (nrepl/eval-expr {:port port :expr expr}))
          result (deref f timeout-ms ::timeout)]
      (if (= result ::timeout)
        (do (future-cancel f)
            {:error :timeout
             :message "Operation timed out. Is a browser tab connected to Epupp?"})
        (let [raw (first (:vals result))
              parsed (parse-promise-str raw)]
          (if parsed
            parsed
            {:error :unexpected-response :raw raw}))))
    (catch Exception e
      (if (connection-refused? e)
        {:error :no-relay
         :message (str "Cannot connect to Epupp relay on port " port
                       ". Start it with: bb browser-nrepl --nrepl-port " port)}
        {:error :execution-error
         :message (.getMessage e)}))))

(defn- probe-connection [{:keys [port]}]
  (try
    (let [f (future (nrepl/eval-expr {:port port :expr "(+ 1 2)"}))
          result (deref f 2000 ::timeout)]
      (if (= result ::timeout)
        (do (future-cancel f) :no-tab)
        (if (= "3" (first (:vals result)))
          :ok
          :unexpected)))
    (catch Exception e
      (if (connection-refused? e)
        :no-relay
        :unexpected))))

;; --- Epupp FS Wrappers ---

(defn- remote-ls [{:keys [port]}]
  (eval-epupp {:port port
               :expr (wrap-promise-expr "(epupp.fs/ls)")
               :timeout-ms 5000}))

(defn- remote-show [{:keys [port script-name]}]
  (eval-epupp {:port port
               :expr (wrap-promise-expr
                      (str "(epupp.fs/show " (pr-str script-name) ")"))
               :timeout-ms 5000}))

(defn- remote-show-bulk [{:keys [port script-names]}]
  (eval-epupp {:port port
               :expr (wrap-promise-expr
                      (str "(epupp.fs/show " (pr-str (vec script-names)) ")"))
               :timeout-ms 10000}))

(defn- remote-save! [{:keys [port code force?]}]
  (eval-epupp {:port port
               :expr (wrap-promise-expr
                      (str "(epupp.fs/save! " (pr-str code)
                           (when force? " {:fs/force? true}")
                           ")"))
               :timeout-ms 10000}))

;; --- Local File Operations ---

(defn- read-manifest [code]
  (try
    (let [form (edn/read-string code)]
      (when (and (map? form) (:epupp/script-name form))
        form))
    (catch Exception _ nil)))

(defn- normalize-script-name [n]
  (let [normalized (-> n str/lower-case (str/replace #"[\s-]" "_"))]
    (cond-> normalized
      (not (str/ends-with? normalized ".cljs")) (str ".cljs"))))

(defn- collect-local-scripts []
  (->> (fs/glob "." "**/*.cljs")
       (mapv (fn [path]
               (let [code (slurp (str path))
                     manifest (read-manifest code)
                     rel-path (str (fs/relativize "." path))]
                 {:local-path rel-path
                  :script-name (some-> manifest :epupp/script-name normalize-script-name)
                  :code code
                  :manifest manifest})))))

(defn- script-name->local-path [script-name]
  script-name)

(defn- dir-arg?
  "Returns true if arg is a directory prefix (i.e. not a .cljs file path)."
  [arg]
  (not (str/ends-with? arg ".cljs")))

(defn- expand-local-paths
  "Expands args that may be directories into individual .cljs file paths."
  [args]
  (into []
        (mapcat (fn [arg]
                  (if (dir-arg? arg)
                    (mapv str (fs/glob arg "**/*.cljs"))
                    [arg])))
        args))

(defn- expand-remote-names
  "Expands directory args against remote script names.
   File args pass through; directory args filter by prefix."
  [args all-remote-names]
  (let [{dirs true files false} (group-by dir-arg? args)
        prefixes (mapv #(cond-> % (not (str/ends-with? % "/")) (str "/")) dirs)]
    (into (vec files)
          (when (seq prefixes)
            (filter (fn [n] (some #(str/starts-with? n %) prefixes))
                    all-remote-names)))))

;; --- Shared CLI + Error Plumbing ---

(defn- abort! [message exit-code]
  (binding [*out* *err*]
    (println message))
  (throw (ex-info message {:babashka/exit exit-code})))

(defn- ensure-connected! [port]
  (case (probe-connection {:port port})
    :ok nil
    :no-relay (abort! (str "Cannot connect to Epupp relay on port " port ".\n"
                           "  Start it with: bb browser-nrepl --nrepl-port " port)
                      1)
    :no-tab (abort! (str "Relay running on port " port " but no browser tab connected.\n"
                         "  Open a page and click Connect in the Epupp popup.")
                    1)
    (abort! (str "Unexpected response from Epupp relay on port " port ".") 1)))

(defn- epupp-script? [name]
  (str/starts-with? name "epupp/"))

;; --- Commands ---

(defn download [{:keys [args opts]}]
  (let [port (or (:port opts) 1339)
        force? (:force opts)
        dry-run? (:dry-run opts)]
    (ensure-connected! port)
    (let [has-dirs? (some dir-arg? args)
          all-remote (when (or (empty? args) has-dirs?)
                       (let [{:keys [ok error]} (remote-ls {:port port})]
                         (when error (abort! (str "Failed to list remote scripts: " error) 1))
                         (->> ok (map :fs/name) (remove epupp-script?))))
          script-names (cond
                         (empty? args) all-remote
                         has-dirs? (expand-remote-names args all-remote)
                         :else args)
          contents (if (= 1 (count script-names))
                     (let [{:keys [ok error]} (remote-show {:port port
                                                            :script-name (first script-names)})]
                       (when error (abort! (str "Failed to fetch: " error) 1))
                       {(first script-names) ok})
                     (let [{:keys [ok error]} (remote-show-bulk {:port port
                                                                  :script-names script-names})]
                       (when error (abort! (str "Failed to fetch scripts: " error) 1))
                       ok))]
      (doseq [name script-names]
        (let [code (get contents name)
              local-path (script-name->local-path name)]
          (cond
            (nil? code)
            (println (str "  ⚠ Not found in Epupp: " name))

            (and (fs/exists? local-path) (not force?))
            (println (str "  ⚠ Skipped (exists): " local-path " (use --force to overwrite)"))

            dry-run?
            (println (str "  Would download: " name " → " local-path))

            :else
            (do (fs/create-dirs (fs/parent local-path))
                (spit local-path code)
                (println (str "  ✓ " local-path)))))))))

(defn upload [{:keys [args opts]}]
  (let [port (or (:port opts) 1339)
        force? (:force opts)
        dry-run? (:dry-run opts)]
    (ensure-connected! port)
    (let [scripts (if (seq args)
                    (mapv (fn [path]
                            (let [code (slurp path)]
                              {:local-path path :code code
                               :manifest (read-manifest code)}))
                          (expand-local-paths args))
                    (collect-local-scripts))]
      (doseq [{:keys [local-path code manifest]} scripts]
        (cond
          (nil? manifest)
          (println (str "  ⚠ Skipped (no manifest): " local-path))

          dry-run?
          (println (str "  Would upload: " local-path " → "
                        (normalize-script-name (:epupp/script-name manifest))))

          :else
          (let [{:keys [ok error] :as result} (remote-save! {:port port :code code :force? force?})]
            (cond
              (and error (str/includes? (str error) "FS"))
              (abort! (str "Write failed: " error "\n"
                           "  Enable \"FS REPL Sync\" in Epupp extension settings.")
                      1)

              error
              (println (str "  ✗ " local-path ": "
                            (if (keyword? error)
                              (or (:message result) (name error))
                              error)))

              :else
              (println (str "  ✓ " (:fs/name ok))))))))))

;; --- Diff ---

(defn- run-diff! [script-name remote-content local-content]
  (let [tmp-dir (str (fs/create-temp-dir))
        remote-file (str tmp-dir "/remote")
        local-file (str tmp-dir "/local")]
    (try
      (spit remote-file remote-content)
      (spit local-file local-content)
      (let [{:keys [out]} (process/shell {:out :string :continue true}
                                         "diff" "-u"
                                         "-L" (str "epupp://" script-name)
                                         "-L" script-name
                                         remote-file local-file)]
        (print out))
      (finally
        (fs/delete-tree tmp-dir)))))

(defn diff-cmd [{:keys [args opts]}]
  (let [port (or (:port opts) 1339)]
    (ensure-connected! port)
    (let [has-dirs? (some dir-arg? args)
          all-remote (when (or (empty? args) has-dirs?)
                       (let [{:keys [ok error]} (remote-ls {:port port})]
                         (when error (abort! (str "Failed to list: " error) 1))
                         (->> ok (map :fs/name) (remove epupp-script?))))
          remote-names (cond
                         (empty? args) all-remote
                         has-dirs? (expand-remote-names args all-remote)
                         :else args)
          expanded-local (when (seq args) (expand-local-paths args))
          local-scripts (if expanded-local
                          (mapv (fn [path]
                                  {:local-path path
                                   :script-name path
                                   :code (when (fs/exists? path) (slurp path))})
                                expanded-local)
                          (collect-local-scripts))
          local-by-name (into {} (keep (fn [{:keys [script-name code]}]
                                         (when script-name [script-name code])))
                               local-scripts)
          all-names (into (set remote-names) (keys local-by-name))
          remote-contents (when (seq remote-names)
                            (let [{:keys [ok error]} (remote-show-bulk
                                                      {:port port :script-names remote-names})]
                              (when error (abort! (str "Failed to fetch: " error) 1))
                              ok))
          stats (atom {:identical 0 :different 0 :remote-only 0 :local-only 0})]
      (doseq [name (sort all-names)]
        (let [remote (get remote-contents name)
              local (get local-by-name name)]
          (cond
            (and remote local (= remote local))
            (swap! stats update :identical inc)

            (and remote local)
            (do (swap! stats update :different inc)
                (run-diff! name remote local))

            remote
            (do (swap! stats update :remote-only inc)
                (println (str "  Remote only: " name)))

            local
            (do (swap! stats update :local-only inc)
                (println (str "  Local only: " name))))))
      (let [{:keys [identical different remote-only local-only]} @stats]
        (println (str "\n" identical " identical, " different " different, "
                      remote-only " remote-only, " local-only " local-only"))))))
