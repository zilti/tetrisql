(ns tetrisql.core
  (:use korma.db
        korma.incubator.core)
  (:require [clojure.string :as string]
            [taoensso.nippy :as nippy]))

(declare insert-if-not-nil)
(declare drop-relation!)
(declare apply-config!)
(declare table-exists?)
(declare create-table!)
(declare get-entity)

(def ^{:private true} db-config (atom {:tables {}}))
(defn rstr [& args] (apply str (flatten args)))

;; (defdb db {:classname "org.h2.Driver"
;;            :subprotocol "h2"
;;            :subname (rstr (-> config/config :server :db-url) "/db")
;;            :delimiters ["" ""]})
(defn ld [] (or(get-in @_default [:options :delimiters 0])
              (get-in _default [:delimiters 0])
              "\""))
(defn rd [] (or(get-in @_default [:options :delimiters 1])
              (get-in _default [:delimiters 1])
              "\""))

;;*****************************************************
;; Actions
;;*****************************************************
(defn create-table! "Creates a new table in the database.
tblname = name of the table.
columns = a list/vector of maps with four keys: :name, :type, :args, :default.
args and default are optional, the defaults are none.
The columns id and meta will be created automatically."
  [tblname fields]
  (when-not (table-exists? tblname)
    (swap! db-config (fn swap-config
                       [config]
                       (assoc-in config
                                 [:tables tblname]
                                 (create-entity (name tblname)))))
    (apply-config!)
    (exec-raw (rstr "CREATE TABLE "
                    (ld) (name tblname) (rd)
                    " ("
                    (loop [fields fields
                           stmt ""]
                      (if (empty? fields)
                        stmt
                        (recur
                         (rest fields)
                         (let [field (first fields)]
                           (rstr  stmt
                                  (ld) (:name field) (rd) " " (:type field) " " (:args field)
                                 (insert-if-not-nil ["DEFAULT "
                                                     (:default field) ""]
                                                    "")
                                 (if (= 1 (count fields))
                                   ""
                                   ", "))))))
                    ");"))
    (get-entity tblname)))

(defn drop-table! "Just as the name says - this drops a table."
  [tblname]
  (when (table-exists? tblname)
    (swap! db-config (fn swap-config
                       [config]
                       (let [relations (get-in config [:tables (keyword tblname) :rel])]
                         (for [relation-key relations]
                           (drop-relation!
                            tblname (-> relations relation-key :rel-type) relation-key))
                         (update-in config [:tables] #(dissoc % (keyword tblname))))))
    (apply-config!)
    (exec-raw
     (rstr
      "DROP TABLE " (ld) (name tblname) (rd)))))

(defn create-column! "Creates a new column for the table.
tblname: The name of the table to add the table to.
col: A column definition as a map. Has the same syntax as in create-table!.
before: Optional parameter, if used this has to be a column name."
  ([tblname col]
     (create-column! tblname col nil))
  ([tblname col before]
     (when (table-exists? tblname)
       (exec-raw
        (rstr
         "ALTER TABLE " (ld) (name tblname) (rd) " "
         "ADD COLUMN IF NOT EXISTS " (ld) (:name col) (rd) " " (:type col) " " (:args col)
         (insert-if-not-nil [" DEFAULT " (:default col) ""] "")
         (insert-if-not-nil [(str " BEFORE " (ld)) (:name before) (rd)] ""))))))

(defn drop-column! "Drops the column of the given name from the table with the given name."
  [tblname colname]
  (when (table-exists? tblname)
    (exec-raw
     (rstr
      "ALTER TABLE " (ld) (name tblname) (rd) " "
      "DROP COLUMN IF EXISTS " (name colname)))))

(defn create-relation! "Creates a link between tables.
Doing this will add columns to the tables.
Doing a many-to-many link will create a link table.
Operators:
:> many-to-one - the left-hand table gets a new column \"table2_id\"
:< one-to-many - the right-hand table gets a new column \"table1_id\"
:= one-to-one - the right-hand table gets a new column \"table1_id\"
:>< many-to-many - a link-table named \"table1_table2\" is created.
\"key :< doors\" is the same as \"doors :> key\""
  [tbl1 relation tbl2]
  (let [ent-tbl1 (get-in @db-config [:tables (keyword tbl1)])
        ent-tbl2 (get-in @db-config [:tables (keyword tbl2)])]
    (case relation
      :> (do
           (swap!
            db-config
            (fn swap-config-:>
              [config]
              (-> config
                  (assoc-in
                   [:tables (keyword tbl1) :rel (name tbl2)]
                   (create-rel (-> tbl1 keyword get-entity)
                               (-> tbl2 keyword get-entity)
                               :belongs-to nil))
                  (assoc-in
                   [:tables (keyword tbl2) :rel (name tbl1)]
                   (create-rel (-> tbl2 keyword get-entity)
                               (-> tbl1 keyword get-entity)
                               :has-many nil)))))
           (create-column! tbl1 {:name (str (name tbl2) "_id")
                                 :type "BIGINT"} nil))
      :< (create-relation! tbl2 :> tbl1)
      := (do
           (swap!
            db-config
            (fn swap-config-:=
              [config]
              (-> config
                  (assoc-in
                   [:tables (keyword tbl1) :rel (name tbl2)]
                   (create-rel (-> tbl1 keyword get-entity)
                               (-> tbl2 keyword get-entity)
                               :has-one nil))
                  (assoc-in
                   [:tables (keyword tbl2) :rel (name tbl1)]
                   (create-rel (-> tbl2 keyword get-entity)
                               (-> tbl1 keyword get-entity)
                               :belongs-to nil)))))
           (create-column! tbl2 {:name (str (name tbl1) "_id")
                                 :type "BIGINT"} nil))
      :>< (do
            (swap!
             db-config
             (fn swap-config-:><
               [config]
               (-> config
                   (assoc-in
                    [:tables (keyword tbl1) :rel (name tbl2)]
                    (create-rel (-> tbl1 keyword get-entity)
                                (-> tbl2 keyword get-entity)
                                :many-to-many
                                {:join-table (str (name tbl1) "_" (name tbl2))
                                 :lfk (str (name tbl1) "_id")
                                 :rfk (str (name tbl2) "_id")}))
                   (assoc-in
                    [:tables (keyword tbl2) :rel (name tbl1)]
                    (create-rel (-> tbl2 keyword get-entity)
                                (-> tbl1 keyword get-entity)
                                :many-to-many
                                {:join-table (str (name tbl1) "_" (name tbl2))
                                 :lfk (str (name tbl2) "_id")
                                 :efk (str (name tbl1) "_id")})))))
            (create-column! tbl1 {:name (str (name tbl2) "_id")
                                  :type "BIGINT"} nil)
            (create-column! tbl2 {:name (str (name tbl1) "_id")
                                  :type "BIGINT"} nil)
            (create-table!
             (str (name tbl1) "_" (name tbl2))
             [{:name (str (name tbl1) "_id")
               :type "BIGINT"}
              {:name (str (name tbl2) "_id")
               :type "BIGINT"}])))
    (apply-config!)))

(defn drop-relation! "This releases a previously defined relation.
The syntax is identical to the one at create-relation!."
  [tbl1 relation tbl2]
  (do
    (swap!
     db-config
     (fn swap-config-:>
       [config]
       (-> config
           (update-in
            [:tables (keyword tbl1) :rel] #(dissoc % (name tbl2)))
           (update-in
            [:tables (keyword tbl2) :rel] #(dissoc % (name tbl1))))))
    (apply-config!)
    (case relation
      :> (drop-column! tbl1 {:name (str (name tbl2) "_id")} nil)
      :< (drop-relation! tbl2 :> tbl1)
      := (drop-column! tbl2 {:name (str (name tbl1) "_id")} nil)
      :>< (do
            (drop-column! tbl1 {:name (str (name tbl2) "_id")} nil)
            (drop-column! tbl2 {:name (str (name tbl1) "_id")} nil)
            (drop-table! (str (name tbl1) "_" (name tbl2)))))))

(defn- contains-map?
  [coll cmap]
  (reduce (fn [bool entry]
            (if (= entry cmap)
              true bool))
          false
          coll))

(defn merge-multires "This function reorganizes your result data:
Think of this result-set:
 [{:users_id_2 1, :time 5, :tid 1, :gname \"administrators\", :gid 1, :groups_id 1, :users_id 1, :uname \"zilti\", :uid 1}
 {:users_id_2 1, :time 10, :tid 2, :gname \"administrators\", :gid 1, :groups_id 1, :users_id 1, :uname \"zilti\", :uid 1}
 {:users_id_2 1, :time 5, :tid 1, :gname \"users\", :gid 2, :groups_id 2, :users_id 1, :uname \"zilti\", :uid 1}
 {:users_id_2 1, :time 10, :tid 2, :gname \"users\", :gid 2, :groups_id 2, :users_id 1, :uname \"zilti\", :uid 1}
 {:users_id_2 2, :time 2, :tid 3, :gname \"users\", :gid 2, :groups_id 2, :users_id 2, :uname \"blah\", :uid 2}]

Now, you have the entry with uid 1 four times because of its dependencies. What about turning that into one and nesting the entries?
You have two tables joined in, :groups and :time. Define a key-mapping:
 {:groups [:gid :gname], :time [:tid :time]}
Primary key is :uid.
Now this: (merge-multires result-set :uid key-mapping)
Turns into this:
 {2 {:uid 2, :uname \"blah\", :users_id 2, :groups_id 2, :users_id_2 2, :time [{:time 2, :tid 3}], :groups [{:gname \"users\", :gid 2}]},
1 {:uid 1, :uname \"zilti\", :users_id 1, :groups_id 2, :users_id_2 1, :time [{:time 5, :tid 1} {:time 10, :tid 2}], :groups [{:gname \"administrators\", :gid 1} {:gname \"users\", :gid 2}]}}"
  [result pk key-mapping]
  (reduce
   (fn [new-result record]
     (let [new-result (if (contains? new-result (record pk))
                        new-result
                        (assoc new-result (record pk) {}))]
       (reduce
        (fn [[key-mapping record new-result] i]
          (if-not (empty? key-mapping)
            (if (contains? (new-result (record pk)) (key (first key-mapping)))
              [(dissoc key-mapping (key (first key-mapping)))
               (apply dissoc record (val (first key-mapping)))
               (if (contains-map?
                    ((new-result (record pk)) (key (first key-mapping)))
                    (select-keys record (val (first key-mapping))))
                 new-result
                 (update-in new-result [(record pk) (key (first key-mapping))]
                            #(conj % (select-keys record (val (first key-mapping))))))]
              (recur [key-mapping
                      record
                      (assoc-in new-result [(record pk) (key (first key-mapping))] [])] i))
            (update-in new-result [(record pk)] #(merge % record))))
        [key-mapping record new-result]
        (range (inc (count key-mapping))))))
   {}
   result))


(defn bootstrap-entity "This creates a new entity without creating a table.
The entity is then available via get-entity."
  [tblname]
  (swap!
   db-config
   #(assoc-in % [:tables (keyword tblname)]
              (create-entity (name tblname)))))

;;*****************************************************
;; Intern Utilities
;;*****************************************************
(defn- correct-case-key
  [key]
  (if-let [case-fun (-> @_default :options :naming :keys)]
    (apply (comp keyword case-fun) [(name key)])
    (keyword key)))

(defn- resolve-has-one
  [result])

(defn- resolve-has-many
  [result])

(defn- resolve-belongs-to
  [result])

(defn- resolve-many-to-many
  [result])

;;*****************************************************
;; Utilities
;;*****************************************************
(defn insert-if-not-nil "Used internally, this will yield a string composed of
prefix, value and postfix if value is not nil, else it will yield nilval."
  [[prefix value postfix] nilval]
  (if-not (nil? value)
    (rstr prefix value postfix)
    nilval))

(defn table-exists? "Returns true if the table of the given name exists, else false."
  [tblname]
  (not (empty? (exec-raw [(str "SHOW COLUMNS FROM " (ld) (name tblname) (rd)) []] :results))))

(defn apply-config! "Stores the internal TetriSQL config into the database."
  []
  (if (empty? (select (get-entity :tetris_cfg) (where {:key "db_cfg"})))
    (exec-raw ["INSERT INTO tetris_cfg (key, value) VALUES (?, ?)" ["db_cfg" (nippy/freeze-to-bytes @db-config)]])
    (exec-raw ["UPDATE tetris_cfg SET value = ? WHERE key = ?" [(nippy/freeze-to-bytes @db-config) "db_cfg"]])))

(defn load-config! "Loads the internal TetriSQL config from the database."
  []
  (when (table-exists? "tetris_cfg")
    (let [result (select (get-entity :tetris_cfg) (where {:key "db_cfg"}))]
      (swap! db-config
             (fn config-swap
               [_]
               (-> result first ((correct-case-key :VALUE)) nippy/thaw-from-bytes))))))

(defn col-value "shortcut for setting a column-value instead of an expression at e.g. a column :default."
  [str]
  (str (ld) str (rd)))

(defn ret-id "Extracts the return id from the given insert- or update-action."
  [res]
  (-> res first val))

(defn get-entity "Returns the entity associated with the given table name."
  [tblname]
  (get-in @db-config [:tables (keyword tblname)]))

(defn relations-select* "This prepares a composable select clause to include all relations
of the given table. Usage: Just as you would use korma's select*."
  [tblname]
  (let [apply-relations
        (fn apply-relations
          [sel]
          (let [relations (get-in @db-config [:tables (keyword tblname) :rel])]
            (reduce
             (fn reduce-relations
               [sel relation]
               (-> sel
                   (with (get-entity (key relation)))))
             sel relations)))]
    (-> (select* (get-entity tblname))
        apply-relations)
    ))

;;*****************************************************
;; Macros
;;*****************************************************
(defmacro create-table-prefix "Creates a function which works the same as create-table,
but inserts certain columns by default.
prefixname: A name for the function.
columns: A list of column definitions, with the same syntax as in create-table!."
  ([prefixname columns]
     `(fn ~prefixname [tblname# cols#]
        (create-table! tblname# (flatten (conj ~columns cols#)))))
  ([columns]
     `(fn [tblname# cols#]
        (create-table! tblname# (flatten (conj ~columns cols#))))))

(defmacro dotbl* "This works the same as insert*, select*, update*, delete* with a little addition
that you have to say which action to use.
action: one of insert, select, update or delete.
tblname: the name of the table."
  [action tblname]
  `(~(symbol (str (name action) "*")) (get-entity ~(keyword (name tblname)))))

(defmacro dotbl "Just as the korma equivalents insert, select, update and delete, this is the
non-composable variant of dotbl."
  [action tblname & body]
  `(let [query# (-> (dotbl* ~action ~tblname)
                   ~@body)]
     (exec query#)))

;;*****************************************************
;; Bootstrap
;;*****************************************************
(defn init-tetris "Init TetriSQL with your default database."
  []
  (if-not (table-exists? :tetris_cfg)
    (do
      (exec-raw (rstr "CREATE TABLE "
                      (ld) "tetris_cfg" (rd)
                      " ("
                      (ld) "id" (rd) " BIGINT AUTO_INCREMENT PRIMARY KEY, "
                      (ld) "key" (rd) " VARCHAR, "
                      (ld) "value" (rd) " BINARY"
                      ");"))
      (bootstrap-entity :tetris_cfg)
      (apply-config!))
    (do
      (bootstrap-entity :tetris_cfg)
      (load-config!))))

;; (korma.db/defdb h2 {:classname "org.h2.Driver"
;;                     :subprotocol "h2"
;;                     :subname "mem:"
;;                     :delimiters ["" ""]
;;                     :naming {:keys string/lower-case}})
;(init-tetris)