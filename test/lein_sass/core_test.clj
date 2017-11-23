(ns lein-sass.core-test
  (:require [clojure.test :refer :all]
            [leiningen.sass :refer :all]))

(deftest test-compiler
  (is (sass "test/test.sass")))
