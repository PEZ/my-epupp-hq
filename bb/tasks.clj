(ns tasks
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [sci.nrepl.browser-server :as bp]))

(defn browser-nrepl
  "Start browser nREPL"
  [{:keys [nrepl-port websocket-port]
    :or {nrepl-port 1339
         websocket-port 1340}}]
  (bp/start! {:nrepl-port nrepl-port :websocket-port websocket-port})
  (deref (promise)))

(def ^:private docs-sync-files
  {"docs/repl-fs-sync.md" "repl-fs-sync.md"
   "README.md"            "epupp-README.md"})

(defn- sync-note [src]
  (let [timestamp (.format (java.time.LocalDateTime/now)
                           (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm"))]
    (str "> [!NOTE]\n"
         "> This file is synced from the [Epupp repository](https://github.com/PEZ/epupp)\n"
         "> (`" src "`).\n"
         "> Last synced: " timestamp ".\n"
         "> To resync: `bb docs-sync`\n\n")))

(defn docs-sync
  "Sync docs from the epupp GitHub repository"
  []
  (let [base-url "https://raw.githubusercontent.com/PEZ/epupp/master/"
        docs-dir (str (fs/cwd) "/docs")]
    (fs/create-dirs docs-dir)
    (doseq [[src dest] docs-sync-files]
      (let [{:keys [status body]} (http/get (str base-url src)
                                            {:throw false})]
        (if (= 200 status)
          (do (spit (str docs-dir "/" dest) (str (sync-note src) body))
              (println "  ✓" src "->" dest))
          (println "  ✗" src "— HTTP" status))))
    (println "Done.")))

(comment
  (browser-nrepl {:nrepl-port 1339 :websocket-port 1340})
  (docs-sync)
  :rcf)