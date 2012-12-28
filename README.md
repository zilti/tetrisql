# TetriSQL

A drop-in replacement for korma's scarce entity management and a tool to manage your tables and columns.

## Usage
Get the game:
```clojure
[tetrisql "0.1.0"]
```

Start the game with
```clojure
(use 'tetrisql.core)
(require '[korma.db :as korma])
```
And choose your options:
```clojure
(korma/defdb h2 {:classname "org.h2.Driver"
                 :subprotocol "h2"
                 :subname "~/db")
                 :delimiters [\" \"]})
;; Start the game!
(init-tetris)
```
Decide wisely where to put your bricks
```clojure
(create-table! :playfield
               [{:name "brick"
                 :type "INT"}
                {:name "color"
                 :type "VARCHAR"
                 :default (col-value "pink")}])
(create-table! :player
               [{:name "name"
                 :type "VARCHAR"}
                {:name "points"
                 :type "BIGINT"
                 :default 0}])
```
And rearrange them later
```clojure
(create-column!
 :player {:name "anger_level"
          :type "BIGINT"})
```
Connect them
```clojure
(create-relation! :playfield := :player)
;; :> many-to-one - the left-hand table gets a new column "table2_id"
;; :< one-to-many - the right-hand table gets a new column "table1_id"
;; := one-to-one - the right-hand table gets a new column "table1_id"
;; :>< many-to-many - a link-table named "table1_table2" is created, and both tables get a new column.
;; "key :< doors" is the same as "doors :> key
```
Or just throw them all away
```clojure
(drop-relation! :playfield := :player)
(drop-column! :player :anger_level)
(drop-table! :player)
```

Your game will be saved automatically.

## License

Copyright © 2012 Daniel Ziltener

Distributed under the Eclipse Public License, the same as Clojure.
