(ns trident.datomic-cloud
  (:require [datomic.client.api :as d]
            [trident.util :as u]
            [trident.util.datomic :as ud]
            [trident.datomic-cloud.client :as client]))

(defn init-conn
  "Initializes and returns Datomic cloud connection.

  `config` is a map with the following keys:
   - `:db-name`: the name of a database that will be created
   - `:schema`: the datomic schema in compact format. See
     [[trident.util.datomic/datomic-schema]].
   - `:local-tx-fns?`: if true, transaction functions will be evaluated locally.
     See [[trident.util.datomic/eval-tx-fns]]
   - `:client-cfg`: if `client` is omitted, this will be used to create one."
  ([client config]
   (do
     (d/create-database client (select-keys config [:db-name]))
     (let [conn (client/connect client (select-keys config [:db-name :local-tx-fns?]))]
       (doseq [schema (u/split-by #(empty? (select-keys % [:db/tupleAttrs :db.entity/attrs]))
                                  (ud/datomic-schema (:schema config)))
               :when (not-empty schema)]
         (d/transact conn {:tx-data schema}))
       conn)))
  ([config]
   (init-conn (d/client (:client-cfg config)) config)))

(defn pull-many
  "Ordering is not preserved. If `map-from-key` is provided, returns a map. See
  [[trident.util/map-from]]. nil keys are `dissoc`-ed."
  ([db pull-expr eids]
   (flatten
     (d/q [:find (list 'pull '?e pull-expr)
           :in '$ '[?e ...]]
          db eids)))
  ([db pull-expr map-from-key eids]
   (let [result (u/map-from map-from-key (pull-many db pull-expr eids))]
     (dissoc result nil))))

(defn add-retract [db eid & kvs]
  (let [kvs (partition 2 kvs)
        ks (map first kvs)
        ent (delay (d/pull db ks eid))]
    (for [[k v] kvs
          :when (or (some? v) (some? (@ent k)))]
      (if (some? v)
        [:db/add eid k v]
        [:db/retract eid k (@ent k)]))))

(defn pull-in [db path lookup]
  (let [[fst & rst] (reverse path)
        pattern (reduce (fn [pattern k]
                          [{k pattern}])
                  [fst]
                  rst)]
    (get-in (d/pull db pattern lookup) path)))

(defn pull-attr [db attr lookup]
  (pull-in db [attr] lookup))

(defn pull-id [db lookup]
  (pull-attr db :db/id lookup))

(defn upsert-component [db lookup attr m]
  {:db/id lookup
   attr (u/assoc-some m
          :db/id (pull-in db [attr :db/id] lookup))})
