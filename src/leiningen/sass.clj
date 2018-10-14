(ns leiningen.sass
  (:require [clojure.set :refer [rename-keys]]
            [clojure.java.io :as io]
            [clojure.pprint]
            [clojure.string :as s])
  (:import
    java.io.File
    [java.nio.file FileSystems Paths StandardWatchEventKinds]
    [javax.script Invocable ScriptEngineManager]))

(def compiled? (atom false))
(defonce engine (let [e (.getEngineByName (ScriptEngineManager.) "nashorn")]
                  (.eval e "function setTimeout(f) {f();};")
                  (.eval e (io/reader (io/resource "sass.sync.js")))
                  (.eval e "var source = '';")
                  (.eval e "var options = '';")
                  (.eval e "var output = '';")
                  (.eval e "var output_formatted = '';")
                  (.eval e "var output_map = '';")
                  (.eval e "var output_text = '';")
                  (.eval e "var output_column = '';")
                  (.eval e "var output_line = '';")
                  (.eval e "var output_status = '';")
                  (.eval e "var output_file = '';")
                  (.eval e "var input_relative_path = '';")
                  (.eval e (str "function setSourceAndOptions(input, input_path, output_name) {"
                                "source = input;"
                                "input_relative_path = input_path;"
                                "options = {inputPath: input_path, outputPath: output_name};"
                                "};"))
                  (.eval e (str "function setOutput(result) {"
                                "output = result;"
                                "output_formatted = result.formatted;"
                                "output_map = result.map;"
                                "if (output_map != undefined) {"
                                "  if ('sourcesContent' in output_map) { delete output_map.sourcesContent; }"
                                "  if ('sourceRoot' in output_map) { delete output_map.sourceRoot; }"
                                "  output_map.sources = [input_relative_path];"
                                "}"
                                "output_text = result.text;"
                                "output_column = result.column;"
                                "output_line = result.line;"
                                "output_status = result.status;"
                                "output_file = result.file;"
                                "};"))
                  (.eval e (str "function simpleIncludes(arr, value) {"
                                "output = false;"
                                "arr.forEach(function(a, b, c) {"
                                "  if (a === value) {"
                                "    output = true;"
                                "  }"
                                "});"
                                "return output;"
                                "};"))
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

(defn compile-file [file relative-input-path output-file-name]
  (let [source (slurp file)]
    (.invokeFunction (cast Invocable engine) "setSourceAndOptions" (into-array [source
                                                                                relative-input-path
                                                                                output-file-name]))
    (.eval engine "Sass.compile(source, options, setOutput)")))

(defn find-assets [f ext]
  (when f
    (if (.isDirectory f)
      (->> f
           file-seq
           (filter (fn [file] (-> file .getName (.endsWith ext)))))
      [f])))

(defn ext-sass->css [file-name]
  (str (subs file-name 0 (.lastIndexOf file-name ".")) ".css"))

(defn drop-common-prefix [dir-path file-path]
  (loop [[f1 & r1 :as c1] (s/split file-path #"/")
         [f2 & r2 :as c2] (s/split dir-path #"/")]
    (cond
      (not f2) [c1 c2]
      (not f1) [c1 c2]
      (= f1 f2) (recur r1 r2)
      :default [c1 c2])))

(defn relative-path [dir-path file-path]
  (let [[c1 c2] (drop-common-prefix dir-path file-path)
        prefix  (s/join "/" (repeat (count c2) ".."))
        suffix  (s/join "/" c1)]
    (str prefix "/" suffix)))

(defn compile-assets [files target source]
  (doseq [file files]
    (let [file-name           (.getName file)
          file-path           (.getPath file)
          file-source-relative-path (-> (re-pattern (str source "(.*)" file-name))
                                        (re-find file-path)
                                        second)
          _                   (println "compiling" file-name)
          output-file-name    (ext-sass->css file-name)
          output-file-path    (str target file-source-relative-path output-file-name)
          relative-input-path (relative-path target file-path)
          _                   (compile-file file relative-input-path output-file-name)
          formatted           (.eval engine "output_formatted")
          map                 (.eval engine "JSON.stringify(output_map,null,2)")
          text                (.eval engine "output_text")
          column              (.eval engine "output_column")
          line                (.eval engine "output_line")
          status              (.eval engine "output_status")]
      (if (pos? status)
        (do
          (println (format "%s:%s:%s failed to compile:" file-path line column))
          (println formatted))
        (do
          (println "compiled successfully")
          (let [map-file-path (str output-file-path ".map")]
            (io/make-parents output-file-path)
            (spit output-file-path text)
            (spit output-file-path (format "\n/*# sourceMappingURL=%s.map */\n" output-file-name)
                  :append true)
            (spit map-file-path map)))))))

(defn name->bare-name
  "Given a file starting with an underscore and having a file extension, returns the name only (e.g. '_foo.scss' -> 'foo')"
  [file]
  (let [[_ _ name _] (re-find #"(_)(.*)(\.)" file)]
    name))

(defn escape-chars
  [text]
  (loop [[c & txt] text
         result ""]
    (if c
      (recur txt
             (str result (case c
                           \' "\\'"
                           \newline ""
                           c)))
      result)))

(defn register-files-for-import
  "sass.js emulates a file system in memory. So files that are imported (files starting with an underscore)
   needs to be registered with the in memory file system."
  [files]
  (let [partial-files (for [file files
                            :let [path (.getPath file)
                                  name (.getName file)]
                            :when (= (first name) \_)]
                        path)
        mappings (str "{"
                      (apply str
                             (interpose ", " (map (fn [path]
                                                    (str "'" path "': '" (-> path slurp escape-chars) "'" ))
                                                  partial-files)))
                      "}")]
    (.eval engine (str "Sass.writeFile("
                       mappings ", "
                       "function callback(result) {});"))))

(defn importer-callback
  "Hook into the compilation process to process Sass's @import calls"
  [files]
  (let [partial-files-json (for [file files
                                 :let [path (.getPath file)
                                       path-without-name (->> path (re-find #"(.*)/[^/]*$") last)
                                       name (.getName file)
                                       bare-name (name->bare-name name)]
                                 :when (= (first name) \_)]
                             (str "'{\"pathWithoutName\": \"" path-without-name "\", \"path\": \"" path "\", \"bareName\": \"" bare-name "\"}'"))]
    (.eval engine (str "Sass.importer(function(request, done) { "
                       "if (request.path) { done(); } "
                       "else { "
                       (str "[" (apply str (interpose ", " partial-files-json)) "]") ".forEach(function(fileJson, index, arr) { "
                       "fileJson = JSON.parse(fileJson);"
                       "if (simpleIncludes(Sass.getPathVariations(fileJson.pathWithoutName + '/' + fileJson.bareName), request.resolved.substring(1))) {"
                       "done({"
                       "path: fileJson.path"
                       "})}});}});"))))

(defn handle-imports
  [files]
  (register-files-for-import files)
  (importer-callback files))

(defn sass [{{:keys [source target]} :sass} & opts]
  (let [find-files #(concat (find-assets (io/file source) ".sass")
                            (find-assets (io/file source) ".scss"))
        find-files-without-partials #(filter (fn [file] (-> file .getName (.startsWith "_") not))
                                             %)
        files (find-files)
        files-without-partials (find-files-without-partials files)
        watch-files (atom {:files files
                           :files-without-partials files-without-partials})]
    (handle-imports files)
    (if (some #{"watch"} opts)
      (do
        (compile-assets files-without-partials target source)
        (watch-thread source (fn [e]
                               (try
                                 (let [{:keys [files files-without-partials]} @watch-files]
                                   (register-files-for-import files)
                                   (compile-assets files-without-partials target source))
                                 ;; If user changes a file's name, this exception is thrown
                                 (catch java.io.FileNotFoundException ex
                                   (println (.getMessage ex))
                                   (let [files (find-files)
                                         files-without-partials (find-files-without-partials files)]
                                     (swap! watch-files assoc :files files
                                            :files-without-partials files-without-partials)))))))
      (when (not @compiled?)
        (compile-assets files-without-partials target source)
        (reset! compiled? true)))))
