(ns leiningen.sass
  (:require [clojure.set :refer [rename-keys]]
            [clojure.java.io :as io]
            [clojure.pprint])
  (:import
    java.io.File
    [java.nio.file FileSystems Paths StandardWatchEventKinds]
    [javax.script Invocable ScriptEngineManager]))

(def compiled? (atom false))
(defonce engine (let [e (.getEngineByName (ScriptEngineManager.) "nashorn")]
                  (.eval e (io/reader (io/resource "sass.min.js")))
                  (.eval e "var source = '';")
                  (.eval e "function setSource(input) {source = input;};")
                  e))

(defn register-events! [dir watch-service]
  (.register dir
             watch-service
             (into-array
               [StandardWatchEventKinds/ENTRY_CREATE
                StandardWatchEventKinds/ENTRY_MODIFY
                StandardWatchEventKinds/ENTRY_DELETE
                StandardWatchEventKinds/OVERFLOW])
             (into-array [(com.sun.nio.file.SensitivityWatchEventModifier/HIGH)])))

(defn watch-loop [watch-service handler]
  (while true
    (when-let [k (.take watch-service)]
      (when-let [events (not-empty (.pollEvents k))]
        (handler (first events))
        (.pollEvents k)
        (.reset k)))))

(defn watch [path handler]
  (let [dir (-> path (io/file) (.toURI) (Paths/get))]
    (with-open [watch-service (.newWatchService (FileSystems/getDefault))]
      (register-events! dir watch-service)
      (watch-loop watch-service handler))))

(defn watch-thread [path handler]
  (println "watching for changes in" path)
  (doto
    (Thread. #(watch path handler))
    (.start)
    (.join)))

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

(defn compile-assets [files target]
  (doseq [file files]
    (let [file-name (.getName file)
          _ (println "compiling" file-name)
          compiled  (compile-file file)]
      (if (string? compiled)
        (do
          (println "compiled successfully")
          (let [output-file-path (str target File/separator (ext-sass->css file-name))]
            (io/make-parents output-file-path)
            (spit output-file-path compiled)))
        (do
          (println "failed to compile:" file-name)
          (clojure.pprint/pprint compiled))))))

(defn sass [{{:keys [source target]} :sass} & opts]
  (let [files (concat (find-assets (io/file source) ".sass")
                      (find-assets (io/file source) ".scss"))]
    (if (some #{"watch"} opts)
      (do
        (compile-assets files target)
        (watch-thread source (fn [e] (compile-assets files target))))
      (when (not @compiled?)
        (compile-assets files target)
        (reset! compiled? true)))))
