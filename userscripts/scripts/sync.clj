(ns sync
  (:require [babashka.cli :as cli]
            [babashka.nrepl-client :as nrepl]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- coercion-flag-sets [coercions]
  {:bool-flags   (into #{} (for [[k v] coercions :when (= v :boolean)] (str "--" (name k))))
   :valued-flags (into #{} (for [[k v] coercions :when (not= v :boolean)] (str "--" (name k))))})

(defn- classify-arg [bool-flags valued-flags arg]
  (cond
    (bool-flags arg) :bool-flag
    (valued-flags arg) :valued-flag
    :else :positional))

(defn- args-step [{:keys [bool-flags valued-flags]} flags positional remaining]
  (when-let [[arg & more] (seq remaining)]
    (case (classify-arg bool-flags valued-flags arg)
      :bool-flag [more (conj flags arg) positional]
      :valued-flag [(rest more) (conj flags arg (first more)) positional]
      :positional [more flags (conj positional arg)])))

(defn- split-flags-and-positional [flag-info args]
  (loop [remaining args flags [] positional []]
    (if-some [[r f p] (args-step flag-info flags positional remaining)]
      (recur r f p)
      [flags positional])))

(defn parse-cli
  "Parse CLI args allowing flags in any position (before or after positional args).
   babashka.cli/parse-args stops parsing opts after the first positional arg,
   so we partition into flags (with their values) and positional args, then
   recombine with flags first."
  [args cli-opts]
  (let [flag-info (coercion-flag-sets (:coerce cli-opts))
        [flags positional] (split-flags-and-positional flag-info args)]
    (cli/parse-args (concat flags positional) cli-opts)))

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

(defn- parse-nrepl-result [result]
  (let [raw (first (:vals result))
        parsed (parse-promise-str raw)]
    (or parsed {:error :unexpected-response :raw raw})))

(defn- handle-eval-error [e port]
  (if (connection-refused? e)
    {:error :no-relay
     :message (str "Failed connecting to Epupp relay on port " port)}
    {:error :execution-error
     :message (.getMessage e)}))

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
        (parse-nrepl-result result)))
    (catch Exception e
      (handle-eval-error e port))))

(defn- interpret-probe-result [result]
  (if (= "3" (first (:vals result)))
    :ok
    :unexpected))

(defn- probe-connection [{:keys [port]}]
  (try
    (let [f (future (nrepl/eval-expr {:port port :expr "(+ 1 2)"}))
          result (deref f 2000 ::timeout)]
      (if (= result ::timeout)
        (do (future-cancel f) :no-tab)
        (interpret-probe-result result)))
    (catch Exception e
      (if (connection-refused? e) :no-relay :unexpected))))

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
  (let [base (if (str/ends-with? n ".cljs")
               (subs n 0 (- (count n) 5))
               n)]
    (-> base
        str/lower-case
        (str/replace #"[.]+" "/")
        (str/replace #"[\s-]+" "_")
        (str/replace #"[^a-z0-9_/]" "")
        (str ".cljs"))))

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
                    (mapv str (fs/glob arg "**.cljs"))
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
  (throw (ex-info "" {:babashka/exit exit-code})))

(defn- ensure-connected! [port]
  (case (probe-connection {:port port})
    :ok nil
    :no-relay (abort! (str "Cannot connect to Epupp relay on port " port ".\n"
                           "  Start it with: bb browser-nrepl --nrepl-port " port)
                      1)
    :no-tab (abort! (str "Relay running on port " port " but no browser tab connected.\n"
                         "  Open a page and click Connect in the Epupp popup.")
                    1)
    (abort! (str "Unexpected response from Epupp relay on port " port ".") 1))
  ;; Probe FS Sync: built-in epupp/ scripts always exist, so empty = FS Sync disabled
  (let [{:keys [ok error]} (eval-epupp {:port port
                                        :expr (wrap-promise-expr "(epupp.fs/ls {:fs/ls-hidden? true})")
                                        :timeout-ms 5000})]
    (when (or error (empty? ok))
      (abort! (str "FS REPL Sync is not enabled on port " port ".\n"
                   "  Enable it in Epupp extension settings.")
              1))))

(defn- epupp-script? [name]
  (str/starts-with? name "epupp/"))

;; --- Commands ---

(defn- list-user-scripts [{:keys [port]}]
  (let [{:keys [ok error]} (remote-ls {:port port})]
    (when error (abort! (str "Failed to list remote scripts: " error) 1))
    (->> ok (map :fs/name) (remove epupp-script?))))

(defn- resolve-script-names [{:keys [args port]}]
  (cond
    (empty? args) (list-user-scripts {:port port})
    (some dir-arg? args) (expand-remote-names args (list-user-scripts {:port port}))
    :else args))

(defn- fetch-remote-contents [{:keys [port script-names]}]
  (if (= 1 (count script-names))
    (let [{:keys [ok error]} (remote-show {:port port :script-name (first script-names)})]
      (when error (abort! (str "Failed to fetch: " error) 1))
      {(first script-names) ok})
    (let [{:keys [ok error]} (remote-show-bulk {:port port :script-names script-names})]
      (when error (abort! (str "Failed to fetch scripts: " error) 1))
      ok)))

(defn- gather-local-scripts [args]
  (let [expanded-local (when (seq args) (expand-local-paths args))]
    (if expanded-local
      (mapv (fn [path]
              {:local-path path
               :script-name path
               :code (when (fs/exists? path) (slurp path))})
            expanded-local)
      (collect-local-scripts))))

(defn ls [{:keys [args opts]}]
  (let [port (or (:port opts) 3339)]
    (ensure-connected! port)
    (let [names (resolve-script-names {:args args :port port})]
      (doseq [n (sort names)]
        (println (str "  " n))))))

(defn- download-script! [{:keys [script-name code force? dry-run?]}]
  (let [local-path (script-name->local-path script-name)]
    (cond
      (nil? code)
      (println (str "  ⚠ Not found in Epupp: " script-name))

      (and (fs/exists? local-path) (not force?))
      (println (str "  ⚠ Skipped (exists): " local-path " (use --force to overwrite)"))

      dry-run?
      (println (str "  Would download: " script-name " → " local-path))

      :else
      (do (fs/create-dirs (fs/parent local-path))
          (spit local-path code)
          (println (str "  ✓ " local-path))))))

(defn download [{:keys [args opts]}]
  (let [port (or (:port opts) 3339)
        force? (:force opts)
        dry-run? (:dry-run opts)]
    (ensure-connected! port)
    (let [script-names (resolve-script-names {:args args :port port})
          contents (fetch-remote-contents {:port port :script-names script-names})]
      (run! #(download-script! {:script-name %
                                :code (get contents %)
                                :force? force?
                                :dry-run? dry-run?})
            script-names))))

(defn- upload-script! [{:keys [local-path code manifest port force? dry-run?]}]
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
        (println (str "  ✓ " (:fs/name ok)))))))

(defn upload [{:keys [args opts]}]
  (let [port (or (:port opts) 3339)
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
      (run! #(upload-script! (assoc % :port port :force? force? :dry-run? dry-run?))
            scripts))))

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

(defn- compare-script [remote-contents local-by-name script-name]
  (let [remote (get remote-contents script-name)
        local (get local-by-name script-name)
        both? (and remote local)]
    (cond
      (and both? (= remote local))
      :identical

      both?
      (do (run-diff! script-name remote local) :different)

      remote
      (do (println (str "  Remote only: " script-name)) :remote-only)

      local
      (do (println (str "  Local only: " script-name)) :local-only))))

(defn- print-diff-summary [{:keys [identical different remote-only local-only]}]
  (println (str "\n" (or identical 0) " identical, " (or different 0) " different, "
                (or remote-only 0) " remote-only, " (or local-only 0) " local-only")))

(defn diff-cmd [{:keys [args opts]}]
  (let [port (or (:port opts) 3339)]
    (ensure-connected! port)
    (let [remote-names (resolve-script-names {:args args :port port})
          local-scripts (gather-local-scripts args)
          local-by-name (into {} (keep (fn [{:keys [script-name code]}]
                                         (when script-name [script-name code])))
                              local-scripts)
          all-names (sort (into (set remote-names) (keys local-by-name)))
          remote-contents (when (seq remote-names)
                            (fetch-remote-contents {:port port :script-names remote-names}))]
      (print-diff-summary
       (frequencies (mapv #(compare-script remote-contents local-by-name %) all-names))))))
