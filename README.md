# TetriSQL

A drop-in replacement for korma's scarce entity management and a tool to manage your tables and columns.

## Usage

### Beginner
Get the game:
```clojure
[tetrisql "0.1.5-SNAPSHOT"]
```

Start the game with
```clojure
(use 'tetrisql.core
     'korma.incubator.core)
(require '[korma.db :as korma])
```
And choose your options:
```clojure
(korma/defdb h2 {:classname "org.h2.Driver"
                 :subprotocol "h2"
                 :subname "~/db"
                 :delimiters ["" ""]})
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
Personalize them
```clojure
(dotbl insert player
       (korma/values {:name "zilti"
                      :points 50000}))
```

And rearrange them later
```clojure
(if (table-exists? :player)
    (create-column!
     :player {:name "anger_level"
              :type "BIGINT"}))

(-> (dotbl* update player
            (korma/set-fields {:anger_level 180})
            (korma/where {:name "zilti"})))
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

### Expert
TetriSQL has a drop-in replacement for Korma's modification statements.
If they're not sufficient at one place, you can still fall back to use the default ones and get your entities like this:
```clojure
(bootstrap-entity :tblname) ;; This creates an empty entity and inserts it into TetriSQL's config.
		  	    ;; You probably won't need this as entities are implicitly created with
			    ;; create-table! and removed with drop-table!
(relations-select* :tblname) ;; Prepares a korma select* statement to include all relations.
```

## License

Copyright © 2012 Daniel Ziltener

Distributed under the Eclipse Public License, the same as Clojure.
