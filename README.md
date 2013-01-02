# TetriSQL

A drop-in replacement for korma's scarce entity management and a tool to manage your tables and columns.

## Usage

### Beginner
Get the game:
```clojure
[tetrisql "0.1.9-SNAPSHOT"]
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
                 :subname "mem:"
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

### Advanced
TetriSQL has a drop-in replacement for Korma's modification statements.
If they're not sufficient at one place, you can still fall back to use the default ones and get your entities like this:
```clojure
(bootstrap-entity :tblname) ;; This creates an empty entity and inserts it into TetriSQL's config.
		  	    ;; You probably won't need this as entities are implicitly created with
			    ;; create-table! and removed with drop-table!
(relations-select* :tblname) ;; Prepares a korma select* statement to include all relations.
```

### Expert
####Reorganize your result sets
If you like to work with korma's exec-raw and custom SQL code, TetriSQL has a little present for you:
merge-multires.
If you use JOIN statements and have to-many or many-to-many relationships (and probably multiple of those), things can get ugly.
Consider three tables users, groups and time. users has a has-many relationship to time, and users and groups have a join table users_groups because they're many-to-many.
You'll probably end up with something like this:
```clojure
(korma/exec-raw "SELECT * FROM users
JOIN users_groups ON users_groups.users_id = users.uid
JOIN groups ON groups.gid = users_groups.groups_id
JOIN time ON time.users_id = users.uid" :results)
;; Result set:
[{:users_id_2 1, :time 5, :tid 1, :gname "administrators", :gid 1, :groups_id 1, :users_id 1, :uname "zilti", :uid 1} 
{:users_id_2 1, :time 10, :tid 2, :gname "administrators", :gid 1, :groups_id 1, :users_id 1, :uname "zilti", :uid 1} 
{:users_id_2 1, :time 5, :tid 1, :gname "users", :gid 2, :groups_id 2, :users_id 1, :uname "zilti", :uid 1} 
{:users_id_2 1, :time 10, :tid 2, :gname "users", :gid 2, :groups_id 2, :users_id 1, :uname "zilti", :uid 1} 
{:users_id_2 2, :time 2, :tid 3, :gname "users", :gid 2, :groups_id 2, :users_id 2, :uname "blah", :uid 2}]
```
Now that's one hell of an ugly result set!
:uid is the primary key here, and because of the multiple relationships you see that :uid 1 appears 4 times.
Let's sort that stuff! Define which tables contain which columns:
```clojure
;; Key-mapping
{:groups [:gid :gname], :time [:tid :time]}
```
Now put both things together:
```clojure
(merge-multires result-set :uid key-mapping)
;; New result set:
{2 {:uid 2, :uname "blah", :users_id 2, :groups_id 2, :users_id_2 2, :time [{:time 2, :tid 3}], :groups [{:gname "users", :gid 2}]}, 
1 {:uid 1, :uname "zilti", :users_id 1, :groups_id 2, :users_id_2 1, :time [{:time 5, :tid 1} {:time 10, :tid 2}], :groups [{:gname "administrators", :gid 1} {:gname "users", :gid 2}]}}
```


## License

Copyright © 2013 Daniel Ziltener

Distributed under the Eclipse Public License, the same as Clojure.
