(ns cljdetector.storage.storage
  (:require [monger.core :as mg]
            [monger.collection :as mc]
            [monger.operators :refer :all]
            [monger.conversion :refer [from-db-object]]))

(def DEFAULT-DBHOST "localhost")
(def dbname "cloneDetector")
(def partition-size 100)
(def hostname (or (System/getenv "DBHOST") DEFAULT-DBHOST))
;; (def collnames ["files"  "chunks" "candidates" "clones" "statusUpdates"])
(def collnames ["files"  "chunks" "candidates" "clones"])

;; moved this function on the top so it can be reused
(defn get-dbconnection []
  (mg/connect {:host hostname}))

;; added db connection closing (issue: too many TCP connection)
(defn close-dbconnection [conn]
  (mg/disconnect conn))

(defn print-statistics []
  (let [conn (get-dbconnection)
        db (mg/get-db conn dbname)]
    (doseq [coll collnames]
      (println "db contains" (mc/count db coll) coll))
    (close-dbconnection conn)))

(defn clear-db! []
  (let [conn (get-dbconnection)
        db (mg/get-db conn dbname)]
    (doseq [coll collnames]
      (mc/drop db coll))
    (close-dbconnection conn)))

;; (defn count-items [collname]
;;   (let [conn (get-dbconnection)
;;         db (mg/get-db conn dbname)]
;;     (mc/count db collname)
;;     (close-dbconnection conn)))
(defn count-items [collname]
  (let [conn (get-dbconnection)]
    (try
      (let [db (mg/get-db conn dbname)]
        (mc/count db collname))
      (finally
        (close-dbconnection conn)))))


(defn store-files! [files]
  (let [conn (get-dbconnection)
        db (mg/get-db conn dbname)
        collname "files"
        file-parted (partition-all partition-size files)]
    (doseq [file-group file-parted]
      (try
        (doseq [file file-group]
          (try
            (let [file-name (.getPath file)
                  file-contents (slurp file)]
              (mc/insert db collname {:fileName file-name :contents file-contents}))
            (catch Exception e
              (println "Error storing file:" (.getPath file) "Error:" (.getMessage e)))))
        (catch Exception e
          (println "Error processing batch. Skipping batch. Error:" (.getMessage e)))))
    (close-dbconnection conn)))

(defn store-chunks! [chunks]
  (let [conn (get-dbconnection)
        db (mg/get-db conn dbname)
        collname "chunks"
        chunk-parted (partition-all partition-size (flatten chunks))]
    (doseq [chunk-group chunk-parted]
      (mc/insert-batch db collname (map identity chunk-group)))
    (close-dbconnection conn)))

(defn store-clones! [conn clones]
  (let [db (mg/get-db conn dbname)
        collname "clones"
        clones-parted (partition-all partition-size clones)]
    (doseq [clone-group clones-parted]
      (mc/insert-batch db collname (map identity clone-group)))
    (close-dbconnection conn)))

(defn identify-candidates! []
  (let [conn (get-dbconnection)
        db (mg/get-db conn dbname)
        collname "chunks"]
    (mc/aggregate db collname
                   [{$group {:_id {:chunkHash "$chunkHash"}
                             :numberOfInstances {$count {}}
                             :instances {$push {:fileName "$fileName"
                                                :startLine "$startLine"
                                                :endLine "$endLine"}}}}
                    {$match {:numberOfInstances {$gt 1}}}
                    {"$out" "candidates"} ])
    (close-dbconnection conn)))


(defn consolidate-clones-and-source []
  (let [conn (get-dbconnection)
        db (mg/get-db conn dbname)
        collname "clones"]
    (mc/aggregate db collname
                  [{$project {:_id 0 :instances "$instances" :sourcePosition {$first "$instances"}}}
                   {"$addFields" {:cloneLength {"$subtract" ["$sourcePosition.endLine" "$sourcePosition.startLine"]}}}
                   {$lookup
                    {:from "files"
                     :let {:sourceName "$sourcePosition.fileName"
                           :sourceStart {"$subtract" ["$sourcePosition.startLine" 1]}
                           :sourceLength "$cloneLength"}
                     :pipeline
                     [{$match {$expr {$eq ["$fileName" "$$sourceName"]}}}
                      {$project {:contents {"$split" ["$contents" "\n"]}}}
                      {$project {:contents {"$slice" ["$contents" "$$sourceStart" "$$sourceLength"]}}}
                      {$project
                       {:_id 0
                        :contents 
                        {"$reduce"
                         {:input "$contents"
                          :initialValue ""
                          :in {"$concat"
                               ["$$value"
                                {"$cond" [{"$eq" ["$$value", ""]}, "", "\n"]}
                                "$$this"]
                               }}}}}]
                     :as "sourceContents"}}
                   {$project {:_id 0 :instances 1 :contents "$sourceContents.contents"}}])
    (close-dbconnection conn)))


(defn get-one-candidate [conn]
  (let [db (mg/get-db conn dbname)
        collname "candidates"]
    (from-db-object (mc/find-one db collname {}) true)))

(defn get-overlapping-candidates [conn candidate]
  (let [db (mg/get-db conn dbname)
        collname "candidates"
        clj-cand (from-db-object candidate true)]
    (mc/aggregate db collname
                  [{$match {"instances.fileName" {$all (map #(:fileName %) (:instances clj-cand))}}}
                   {$addFields {:candidate candidate}}
                   {$unwind "$instances"}
                   {$project 
                    {:matches
                     {$filter
                      {:input "$candidate.instances"
                       :cond {$and [{$eq ["$$this.fileName" "$instances.fileName"]}
                                    {$or [{$and [{$gt  ["$$this.startLine" "$instances.startLine"]}
                                                 {$lte ["$$this.startLine" "$instances.endLine"]}]}
                                          {$and [{$gt  ["$instances.startLine" "$$this.startLine"]}
                                                 {$lte ["$instances.startLine" "$$this.endLine"]}]}]}]}}}
                     :instances 1
                     :numberOfInstances 1
                     :candidate 1
                     }}
                   {$match {$expr {$gt [{$size "$matches"} 0]}}}
                   {$group {:_id "$_id"
                            :candidate {$first "$candidate"}
                            :numberOfInstances {$max "$numberOfInstances"}
                            :instances {$push "$instances"}}}
                   {$match {$expr {$eq [{$size "$candidate.instances"} "$numberOfInstances"]}}}
                   {$project {:_id 1 :numberOfInstances 1 :instances 1}}])))

(defn remove-overlapping-candidates! [conn candidates]
  (let [db (mg/get-db conn dbname)
        collname "candidates"]
      (mc/remove db collname {:_id {$in (map #(:_id %) candidates)}})))

(defn store-clone! [conn clone]
  (let [db (mg/get-db conn dbname)
        collname "clones"
        anonymous-clone (select-keys clone [:numberOfInstances :instances])]
    (mc/insert db collname anonymous-clone)))

(defn addUpdate! [timestamp message]
  (let [conn (get-dbconnection)
        db (mg/get-db conn dbname)
        collname "statusUpdates"]
    (mc/insert db collname {:timestamp timestamp :message message})
    (close-dbconnection conn)))
