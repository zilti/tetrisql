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
;; (defn- ld [] (or(get-in db [:options :delimiters 0])
;;                 (get-in db [:delimiters 0])))
;; (defn- rd [] (or(get-in db [:options :delimiters 1])
;;                 (get-in db [:delimiters 1])))
(defn- ld [] "")
(defn- rd [] "")

;;*****************************************************
;; Actions
;;*****************************************************
(defn create-table! "Creates a new table in the database.
tblname = name of the table.
columns = a list/vector of maps with five keys: :name, :type, :args, :default, :fulltext?
args, default and fulltext are optional, the defaults are none, nil and false.
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
                                                     (rstr
                                                      (:default field))]
                                                    "")
                                 (if (= 1 (count fields))
                                   ""
                                   ", "))))))
                    ");"))))

(defn drop-table! [tblname]
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

(defn create-column! [tblname col before]
  (when (table-exists? tblname)
    (exec-raw
     (rstr
      "ALTER TABLE " (ld) (name tblname) (rd) " "
      "ADD COLUMN IF NOT EXISTS " (ld) (:name col) (rd) " " (:type col) " " (:args col) " "
      (insert-if-not-nil ["DEFAULT " (:default col)] "")
      ";"))))

(defn drop-column! [tblname colname]
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

(defn drop-relation! [tbl1 relation tbl2]
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

(defn bootstrap-entity [tblname]
  (swap!
   db-config
   #(assoc-in % [:tables (keyword tblname)]
              (create-entity (name tblname)))))

(defn get-entity [tblname]
  (get-in @db-config [:tables (keyword tblname)]))

(defn relations-select*
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
(defn insert-if-not-nil [[prefix value postfix] nilval]
  (if-not (nil? value)
    (rstr prefix value postfix)
    nilval))

(defn table-exists? [tblname]
  (not (empty? (exec-raw [(str "SHOW COLUMNS FROM " (ld) (name tblname) (rd)) []] :results))))

(defn apply-config! []
  (if (empty? (select (get-entity :lopia_db_cfg) (where {:key "db_cfg"})))
    (insert (get-entity :lopia_db_cfg) (values {:key "db_cfg"
                                                :value (str @db-config)}))
    (update (get-entity :lopia_db_cfg)
            (set-fields {:value (str @db-config)})
            (where {:key "db_cfg"}))))

(defn load-config! []
  (when (table-exists? "lopia_db_cfg")
    (let [result (select (get-entity :lopia_db_cfg) (where {:key "db_cfg"}))]
      (swap! db-config
             (fn config-swap
               [_]
               (-> result first :VALUE read-string))))))

;;*****************************************************
;; Macros
;;*****************************************************
(defmacro create-table-prefix
  ([prefixname columns]
     `(fn ~prefixname [tblname cols]
        (create-table! tblname (conj cols ~columns))))
  ([columns]
     `(fn [tblname cols]
        (create-table! tblname (conj cols ~columns)))))

(defmacro dotbl*
  [action tblname]
  `(~(symbol (str (name action) "*")) (get-entity ~(keyword (name tblname)))))

(defmacro dotbl
  [action tblname & body]
  `(let [query# (-> (dotbl* ~action ~tblname)
                   ~@body)]
     (exec query#)))

;; Create a wrapper for connecting to the database b/c of delimiters needed to get extracted
;; Create possibility so say dbname at actions