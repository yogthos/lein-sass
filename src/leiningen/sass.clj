(ns leiningen.sass
  (:require [clojure.java.io :as io])
  (:import
   java.io.File
   [javax.script Invocable ScriptEngine ScriptEngineManager]))

(def compiled? (atom false))
(def engine (.getEngineByName (ScriptEngineManager.) "nashorn"))
(.eval engine (io/reader (io/resource "sass.min.js")))
(.eval engine "var source = '';")
(.eval engine "function setSource(input) {source = input;};")


(defn compile-file [file]
  (let [source (slurp file)]
    (.invokeFunction (cast Invocable engine) "setSource" (into-array [source]))
    (.eval engine "Sass.compile(source)")))

(defn find-assets [f ext]
  (when f
    (if (.isDirectory f)
      (->> f
           file-seq
           (filter (fn [file] (-> file .getName (.endsWith ext)))))
      [f])))

(defn ext-sass->css [file-name]
  (str (subs file-name 0 (.lastIndexOf file-name ".")) ".css"))

(defn sass [{{:keys [source target]} :sass} & opts]
  (when (not @compiled?)
    (let [files (find-assets (io/file source) ".sass")]
      (doseq [file files]

        (let [compiled (compile-file file)
              file-name (.getName file)]
          (println "compling" file-name)
          (if (map? compiled)
            (do
              (println "failed to compile:" file-name)
              (clojure.pprint/pprint compiled))
            (do
              (println "compiled successfully")
              (spit (str target File/separator (ext-sass->css file-name)) compiled)))))
      (reset! compiled? true))))
