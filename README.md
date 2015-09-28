# lein-sass

A minimum dependency Clojure library designed to compile SASS files using [Sass.js](https://github.com/medialize/sass.js) running on Nashorn

## Installing



## Usage

1. git clone this project to your computer
2. run `lein install`
3. add the following to your `project.clj`
    ```clojure
    :plugins [[lein-sass "0.1.0"]]
    :sass {:source "my/sass/dir" :target "my/output/dir"}
    ```

4. start coding!
    * run `lein sass` to compile the assets
    * run `lein sass watch` to watch for changes and recompile files as necessary 




## License

Copyright © 2015 Dmitri Sotnikov

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.

