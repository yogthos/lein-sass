(ns lein-sass.core
  (:require [clojure.java.io :as io])
  (:import [javax.script Invocable ScriptEngine ScriptEngineManager]))

(def engine (.getEngineByName (ScriptEngineManager.) "nashorn"))
(.eval engine (io/reader (io/resource "sass.min.js")))
(.eval engine "var source = '';")
(.eval engine "function setSource(input) {source = input;};")

(defn compile-css [file]
  (let [source (slurp file)]
    (.invokeFunction (cast Invocable engine) "setSource" (into-array [source]))
    (.eval engine "Sass.compile(source)")))
