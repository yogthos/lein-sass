(ns lein-sass.core-test
  (:require [clojure.test :refer :all]
            [lein-sass.core :refer :all]))

(deftest test-compiler
  (is (= ".selector {\n  margin: 10px; }\n  .selector .nested {\n    margin: 5px; }\n"
         (compile-css "test/test.sass"))))

