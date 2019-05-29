(ns trident.build.pom
  (:require [trident.build.xml :refer [xml-replace]]
            [trident.build.util :refer [abspath]]
            [clojure.data.xml :as xml]
            [clojure.tools.deps.alpha.gen.pom :as gen.pom]
            [clojure.java.io :as io]
            [clojure.tools.deps.alpha.reader :refer [read-deps]]
            [clojure.zip :as zip]))

(xml/alias-uri 'pom "http://maven.apache.org/POM/4.0.0")

(defn sync-pom [{:keys [group-id artifact-id version github-repo]}]
  (let [pom-path (abspath "pom.xml")]
    (io/delete-file pom-path true)
    (gen.pom/sync-pom
      (read-deps [(io/file (abspath "deps.edn"))])
      (io/file (abspath ".")))
    (-> pom-path io/file io/input-stream xml/parse
        (xml-replace
          ::pom/groupId group-id
          ::pom/artifactId artifact-id
          ::pom/version version)
        zip/xml-zip
        (zip/insert-child
          (xml/sexp-as-element
            [::pom/scm
             [::pom/connection (str "scm:git:git://github.com/" github-repo ".git")]
             [::pom/developerConnection (str "scm:git:ssh://git@github.com/" github-repo ".git")]
             [::pom/tag version]
             [::pom/url (str "https://github.com/" github-repo)]]))
        zip/root
        xml/indent-str
        (->> (spit pom-path)))))
