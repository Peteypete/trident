(ns trident.build.mono
  "Build tasks for working with monolithic projects. See [[mono]]."
  (:require [clojure.set :refer [union difference]]
            [trident.cli :refer [make-cli expand-cli dispatch]]
            [trident.cli.util :refer [path sppit with-dir rmrf]]
            [me.raynes.fs :as fs]
            [clojure.string :as str]))

(def ^:no-doc docstring
"Creates project directories for a monolithic project.

If no projects are specified, operates on all projects. Configuration is stored
in a `mono.edn` file. Example:
```
{; Individual projects are defined here. All source files are kept in the same
 ; top-level `src` directory, and the keys of :projects define which source
 ; files belong to which projects.
 ;
 ; For example, anything in the trident.util namespace (or in a child namespace)
 ; belongs to the util project. The project directory will be created at
 ; `target/util/`, and a deps.edn file will be created that includes the
 ; dependencies specified here.
 ;
 ; :local-deps specifies dependencies to other projects. For example, the source
 ; files and dependencies of the util project will also be included in the cli
 ; project.
 ;
 ; In the project directories, source files are included by creating symbolic
 ; links to files and directories in the top-level `src` directory. So you
 ; continue to edit files in the top-level `src`, and individual projects will
 ; get the changes immediately.
 :projects
 {repl  {:deps [org.clojure/tools.namespace
                mount
                orchestra
                nrepl]}
  util  {:deps [org.clojure/core.async
                orchestra
                com.taoensso/encore]}
  cli   {:local-deps [util]
         :deps [me.raynes/fs
                org.clojure/tools.cli
                net.java.dev.jna/jna
                com.github.jnr/jnr-posix]}
  build {:local-deps [util cli]
         :deps [org.clojure/data.xml
                org.clojure/data.zip
                deps-deploy
                me.raynes/fs
                jobryant/pack
                org.clojure/tools.deps.alpha]}}

 ; You can specify versions for dependencies here. This way, all projects will
 ; use the same versions.
 :managed-deps
 {com.github.jnr/jnr-posix {:mvn/version \"3.0.49\"}
  com.taoensso/encore {:mvn/version \"2.112.0\"}
  deps-deploy {:mvn/version \"0.0.9\"}
  jobryant/pack {:mvn/version \"1.0\"}
  me.raynes/fs {:mvn/version \"1.4.6\"}
  mount {:mvn/version \"0.1.15\"}
  net.java.dev.jna/jna {:mvn/version \"5.3.1\"}
  nrepl {:mvn/version \"0.6.0\"}
  orchestra {:mvn/version \"2019.02.06-1\"}
  org.clojure/clojure {:mvn/version \"1.9.0\"}
  org.clojure/core.async {:mvn/version \"0.4.490\"}
  org.clojure/data.xml {:mvn/version \"0.2.0-alpha5\"}
  org.clojure/data.zip {:mvn/version \"0.1.3\"}
  org.clojure/tools.cli {:mvn/version \"0.4.2\"}
  org.clojure/tools.deps.alpha {:mvn/version \"0.6.496\"}
  org.clojure/tools.namespace {:mvn/version \"0.2.11\"}}

 ; :aliases and :mvn/repos will be included verbatim in each project's deps.edn
 :aliases {:dev {:extra-deps {trident-repl {:local/root \"../repl\"}}}}
 :mvn/repos {\"datomic-cloud\" {:url \"s3://datomic-releases-1fc2183a/maven/releases\"}}

 ; The following keys will be included in a lib.edn file for each project. :artifact-id
 ; and :git-dir keys will also be automatically included. lib.edn is used as a config
 ; file for build tasks defined in the trident.build namespace.
 :version \"0.1.3-SNAPSHOT\"
 :group-id \"trident\"
 :github-repo \"jacobobryant/trident\"
 :cljdoc-dir \"/home/arch/dev/cljdoc/\"}
```")

(defn- get-deps [projects lib]
  (let [local-deps (loop [deps #{}
                          libs #{lib}]
                     (if (empty? libs)
                       deps
                       (let [local-deps (set (mapcat #(get-in projects [% :local-deps]) libs))
                             deps (union deps libs)]
                         (recur
                           deps
                           (difference local-deps deps)))))
        maven-deps (set (mapcat #(get-in projects [% :deps]) local-deps))]
    [local-deps maven-deps]))

(defn ^{:doc docstring} mono
  [{:keys [projects group-id managed-deps] :as opts} & libs]
  (doseq [lib (or (not-empty (map symbol libs)) (conj (keys projects) group-id))]
    (let [dest (path "target" lib "src" group-id)
          [local-deps maven-deps] (if (= lib group-id)
                                    [(keys projects) (keys managed-deps)]
                                    (get-deps projects lib))
          maven-deps (conj maven-deps 'org.clojure/clojure)
          deps-edn (merge {:paths ["src"]
                           :deps (select-keys managed-deps maven-deps)}
                          (select-keys opts [:mvn/repos :aliases]))
          lib-edn (merge (select-keys opts [:version :group-id :github-repo :cljdoc-dir])
                         {:artifact-id (str lib)
                          :git-dir (path ".")})]
      (rmrf dest)
      (fs/mkdirs dest)
      (doseq [dep local-deps
              ext [".clj" ".cljs" ".cljc" "/"]
              :let [dep (str/replace (name dep) "-" "_")
                    basename (str dep ext)
                    target (path "src" group-id basename)]
              :when (fs/exists? target)]
        (fs/sym-link (path dest basename) target))
      (with-dir (path "target" lib)
        (sppit "deps.edn" deps-edn)
        (sppit "lib.edn" lib-edn)))))

(let [{:keys [cli main-fn help]}
      (make-cli
        {:fn #'mono
         :prog "clj -m trident.build.mono"
         :desc ["Creates project directories for a monolithic project."
                ""
                "If no projects are specified, operates on all projects. `mono.edn` must be in"
                "the current directory. See the docstring for trident.build.mono/mono for"
                "the format of `mono.edn`."]
         :cli-options nil
         :args-spec "[<project(s)>]"
         :config ["mono.edn"]})]
  (def cli cli)
  (def ^{:doc help} -main main-fn))

(defn- wrap-dir* [subcommands {:keys [with-projects all-projects projects]} & args]
  (let [subcommand #(dispatch {:subcommands subcommands} args)]
    (if-some [with-projects (if all-projects (keys projects) with-projects)]
      (doseq [p with-projects]
        (with-dir (path "target" p)
          (subcommand)))
      (subcommand))))

(def ^:no-doc cli-options
  {:with-projects ["-w" "PROJECTS" (str "Colon-separated list of projects. The subcommand will "
                                        "be executed in `target/<project>` for each project.")
                   :parse-fn #(str/split % #":")]
   :all-projects  ["-a" nil (str "Execute the command in each project directory (as defined by"
                                 " mono.edn). Overrides --with-projects.")]})

(defn ^:no-doc wrap-dir [subcommands]
  (expand-cli
    {:fn (partial wrap-dir* subcommands)
     :config ["mono.edn"]
     :cli-options [:with-projects :all-projects]
     :subcommands subcommands}
    cli-options))
