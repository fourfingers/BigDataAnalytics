(ns cljdetector.core
  (:require [clojure.string :as string]
            [cljdetector.process.source-processor :as source-processor]
            [cljdetector.process.expander :as expander]
            [cljdetector.storage.storage :as storage]))

(def DEFAULT-CHUNKSIZE 5)
(def source-dir (or (System/getenv "SOURCEDIR") "/tmp"))
(def source-type #".*\.java")
;; (def source-type #"(?i).*\.java$") ;; (?i) makes it case-insensitive


(defn ts-println [& args]
  (let [timestamp (.toString (java.time.LocalDateTime/now))
        message (apply str (interpose " " args))]
    (println timestamp message)
    (storage/addUpdate! timestamp message)))

(defn maybe-clear-db [args]
  (when (some #{"CLEAR"} (map string/upper-case args))
      (ts-println "Clearing database...")
      (storage/clear-db!)))

(defn maybe-read-files [args]
  (when-not (some #{"NOREAD"} (map string/upper-case args))
    (ts-println "Reading and Processing files...")
    (let [chunk-param (System/getenv "CHUNKSIZE")
          chunk-size (if chunk-param (Integer/parseInt chunk-param) DEFAULT-CHUNKSIZE)
          file-handles (source-processor/traverse-directory source-dir source-type)
          chunks (source-processor/chunkify chunk-size file-handles)]
      ;; add verbosity source path and file regex (issue: manual count vs db count mismatch)
      (ts-println "Storing files..." source-dir source-type)
      (storage/store-files! file-handles)
      ;; add verbosity total file counts (issue: manual count vs db count mismatch)
      (ts-println "Total files found:" (count file-handles))
      (ts-println "Storing chunks of size" chunk-size "...")
      (storage/store-chunks! chunks)))) 

;; split implementation of clone identification and expanding clones
;; (issue: execution failures during expanding clones)
(defn maybe-detect-clones [args]
  (when-not (some #{"NOCLONEID"} (map string/upper-case args))
    (ts-println "Identifying Clone Candidates...")
    (storage/identify-candidates!)
    (ts-println "Found" (storage/count-items "candidates") "candidates")
  ))

;; split implementation of clone identification and expanding clones
;; (issue: execution failures during expanding clones)
(defn maybe-expand-clones [args]
  (when-not (some #{"NOEXPAND"} (map string/upper-case args))
    (ts-println "Expanding Candidates...")
    (expander/expand-clones)))

(defn pretty-print [clones]
  (doseq [clone clones]
    (println "====================\n" "Clone with" (count (:instances clone)) "instances:")
    (doseq [inst (:instances clone)]
      (println "  -" (:fileName inst) "startLine:" (:startLine inst) "endLine:" (:endLine inst)))
    (println "\nContents:\n----------\n" (:contents clone) "\n----------")))

(defn maybe-list-clones [args]
  (when (some #{"LIST"} (map string/upper-case args))
    (ts-println "Consolidating and listing clones...")
    (pretty-print (storage/consolidate-clones-and-source))))


;; Add argument NoExpand
(defn -main
  "Starting Point for All-At-Once Clone Detection
  Arguments:
   - Clear clears the database
   - NoRead do not read the files again
   - NoCloneID do not detect clones
   - NoExpand do not expand clones
   - List print a list of all clones"
  [& args]

  (maybe-clear-db args)
  (maybe-read-files args)
  (maybe-detect-clones args)
  ;;  split implementation of clone identification and expanding clones
  ;; (issue: execution failures during expanding clones)
  (maybe-expand-clones args)
  (maybe-list-clones args)
  (ts-println "Summary")
  (storage/print-statistics))
