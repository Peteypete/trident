(ns trident.web-repl
  (:require [trident.util :as u]
            [immutant.web :as imm]
            [trident.web :as trident]
            [mount.core :as mount :refer [defstate]]
            [datomic.ion.cast :as cast]))

(u/defconfig {:port 8080})

; todo specify handler in config
(defn start-immutant []
  (imm/run
    trident/handler*
    {:port (:port config)}))

(defstate server :start (start-immutant)
                 :stop (imm/stop))

(cast/initialize-redirect :stdout)
