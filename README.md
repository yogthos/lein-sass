# lein-sass

A Clojure library designed to compile SASS files using [Sass.js](https://github.com/medialize/sass.js) running on Nashorn

## Usage

add the following to your `project.clj`

```clojure
:plugins [[lein-sass "0.1.0"]]
:sass {:source "sass" :target "css"}
```

then run `lein sass` to compile the assets.


## License

Copyright © 2015 Dmitri Sotnikov

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
