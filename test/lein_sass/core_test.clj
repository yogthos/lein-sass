(ns lein-sass.core-test
  (:require [clojure.test :refer :all]
            [leiningen.sass :refer :all]))

(deftest test-compiler
  (is (= ".selector {\n  margin: 10px; }\n  .selector .nested {\n    margin: 5px; }\n"
         (sass "test/test.sass"))))

