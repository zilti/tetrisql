(ns tetrisql.core
  (:use korma.db
        korma.incubator.core))

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
(defn- ld [] (or(get-in _default [:options :delimiters 0])
                (get-in _default [:delimiters 0])))
(defn- rd [] (or(get-in _default [:options :delimiters 1])
                (get-in _default [:delimiters 1])))

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
                           (rstr (ld) (:name field) (rd) " " (:type field) " " (:args field)
                                 (insert-if-not-nil ["DEFAULT "
                                                     (:default field) ""]
                                                    "")
                                 (if (= 1 (count fields))
                                   ""
                                   ", "))))))
                    ");"))))

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
      "DROP TABLE " (ld) (name tblname) (rd) ";"))))

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
         (insert-if-not-nil [(str " BEFORE " (ld)) (:name before) (rd)] "")
         ";")))))

(defn drop-column! "Drops the column of the given name from the table with the given name."
  [tblname colname]
  (when (table-exists? tblname)
    (exec-raw
     (rstr
      "ALTER TABLE " (ld) (name tblname) (rd) " "
      "DROP COLUMN IF EXISTS " (name colname) ";"))))

(defn create-relation! "Creates a link between tables.
Doing this will add columns to the tables.
Doing a many-to-many link will create a link table.
Operators:
:> many-to-one - the left-hand table gets a new column \"table2_id\"
:< one-to-many - the right-hand table gets a new column \"table1_id\"
:= one-to-one - the right-hand table gets a new column \"table1_id\"
:>< many-to-many - a link-table named \"table1_table2\" is created, and both tables get a new column.
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
                   [:tables (keyword tbl1) :rel (keyword tbl2)]
                   (create-rel (-> tbl1 keyword get-entity)
                               (-> tbl2 keyword get-entity)
                               :belongs-to nil))
                  (assoc-in
                   [:tables (keyword tbl2) :rel (keyword tbl1)]
                   (create-rel (-> tbl2 keyword get-entity)
                               (-> tbl1 keyword get-entity)
                               :has-many nil)))))
           (create-column! tbl1 {:name (str (name tbl2) "_id")} nil))
      :< (create-relation! tbl2 :> tbl1)
      := (do
           (swap!
            db-config
            (fn swap-config-:=
              [config]
              (-> config
                  (assoc-in
                   [:tables (keyword tbl1) :rel (keyword tbl2)]
                   (create-rel (-> tbl1 keyword get-entity)
                               (-> tbl2 keyword get-entity)
                               :has-one nil))
                  (assoc-in
                   [:tables (keyword tbl2) :rel (keyword tbl1)]
                   (create-rel (-> tbl2 keyword get-entity)
                               (-> tbl1 keyword get-entity)
                               :belongs-to nil)))))
           (create-column! tbl2 {:name (str (name tbl1) "_id")} nil))
      :>< (do
            (swap!
             db-config
             (fn swap-config-:><
               [config]
               (-> config
                   (assoc-in
                    [:tables (keyword tbl1) :rel (keyword tbl2)]
                    (create-rel (-> tbl1 keyword get-entity)
                                (-> tbl2 keyword get-entity)
                                :many-to-many
                                {:join-table (str (name tbl1) "_" (name tbl2))
                                 :lfk (str (name tbl1) "_id")
                                 :rfk (str (name tbl2) "_id")}))
                   (assoc-in
                    [:tables (keyword tbl2) :rel (keyword tbl1)]
                    (create-rel (-> tbl2 keyword get-entity)
                                (-> tbl1 keyword get-entity)
                                :many-to-many
                                {:join-table (str (name tbl1) "_" (name tbl2))
                                 :lfk (str (name tbl2) "_id")
                                 :efk (str (name tbl1) "_id")})))))
            (create-column! tbl1 {:name (str (name tbl2) "_id")} nil)
            (create-column! tbl2 {:name (str (name tbl1) "_id")} nil)
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
            [:tables (keyword tbl1) :rel] #(dissoc % (keyword tbl2)))
           (update-in
            [:tables (keyword tbl2) :rel] #(dissoc % (keyword tbl1))))))
    (apply-config!)
    (case relation
      :> (drop-column! tbl1 {:name (str (name tbl2) "_id")} nil)
      :< (drop-relation! tbl2 :> tbl1)
      := (drop-column! tbl2 {:name (str (name tbl1) "_id")} nil)
      :>< (do
            (drop-column! tbl1 {:name (str (name tbl2) "_id")} nil)
            (drop-column! tbl2 {:name (str (name tbl1) "_id")} nil)
            (drop-table! (str (name tbl1) "_" (name tbl2)))))))

(defn bootstrap-entity "This creates a new entity without creating a table.
The entity is then available via get-entity."
  [tblname]
  (swap!
   db-config
   #(assoc-in % [:tables (keyword tblname)]
              (create-entity (name tblname)))))

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
    (insert (get-entity :tetris_cfg) (values {:key "db_cfg"
                                              :value (str @db-config)}))
    (update (get-entity :tetris_cfg)
            (set-fields {:value (str @db-config)})
            (where {:key "db_cfg"}))))

(defn load-config! "Loads the internal TetriSQL config from the database."
  []
  (when (table-exists? "tetris_cfg")
    (let [result (select (get-entity :tetris_cfg) (where {:key "db_cfg"}))]
      (swap! db-config
             (fn config-swap
               [_]
               (-> result first :VALUE read-string))))))

(defn col-value "shortcut for setting a column-value instead of an expression at e.g. a column :default."
  [str]
  (str (ld) str (rd)))

;;*****************************************************
;; Macros
;;*****************************************************
(defmacro create-table-prefix "Creates a function which works the same as create-table,
but inserts certain columns by default.
prefixname: A name for the function.
columns: A list of column definitions, with the same syntax as in create-table!."
  ([prefixname columns]
     `(fn ~prefixname [tblname cols]
        (create-table! tblname (conj cols ~columns))))
  ([columns]
     `(fn [tblname cols]
        (create-table! tblname (conj cols ~columns)))))

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
                      (ld) "value" (rd) " VARCHAR"
                      ");"))
      (bootstrap-entity :tetris_cfg)
      (apply-config!))
    (load-config!)))