;;; structure.clj -- Receive database stucture and keep it in cache.

;;; Copyright © 2016-2019 - Kostafey <kostafey@gmail.com>

;;; This program is free software; you can redistribute it and/or modify
;;; it under the terms of the GNU General Public License as published by
;;; the Free Software Foundation; either version 2, or (at your option)
;;; any later version.
;;;
;;; This program is distributed in the hope that it will be useful,
;;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;;; GNU General Public License for more details.
;;;
;;; You should have received a copy of the GNU General Public License
;;; along with this program; if not, write to the Free Software Foundation,
;;; Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.  */

(ns ejc-sql.structure
  (:use [ejc-sql.lib])
  (:require
   [clojure.java.jdbc :as j]
   [clojure.string :as s]
   [ejc-sql.connect :as c]
   [ejc-sql.output :as o]
   [ejc-sql.keywords :as k]))

(def cache (atom {}))

(defn- safe-query [db sql & {:keys [row-fn]}]
  "Return `sql` query result or nil in case of error."
  (try
    (j/query db (list sql) {:as-arrays? true
                            :row-fn row-fn})
    (catch Exception _)))

(defn- db->column [db sql & {:keys [row-fn]
                             :or {row-fn identity}}]
  "Execute `sql`, return first column of result set as result list."
  (if sql
    (rest (map first (safe-query db sql :row-fn row-fn)))))

(defn- db->>column [db sql]
  "Execute `sql`, return last column of result set as result list."
  (if sql
    (rest
     (map last
          (j/query db (list sql) {:as-arrays? true})))))

(defn- db->value [db sql & {:keys [row-fn]
                            :or {row-fn identity}}]
  "Execute `sql`, return first value of first column of result set as result."
  (first (db->column db sql :row-fn row-fn)))

(defn get-ms-sql-server-version [db]
  (->
   (filter not-empty
           (.split
            (db->value db "SELECT @@VERSION AS 'SQL Server Version'")
            " "))
   (nth 3)
   Integer/parseInt))

(def default-queries
  {:owners  (fn [& _] "
              SELECT schema_owner
              FROM information_schema.schemata ")
   :schemas (fn [& _] "
              SELECT schema_name
              FROM information_schema.schemata ")
   :tables  (fn [& {:keys [schema]}]
              (format "
               SELECT table_name
               FROM information_schema.tables
               %s
               ORDER BY table_name "
                      (if schema
                        (format " WHERE table_schema = '%s'" schema)
                        "")))
   :views   (fn [& _] "
              SELECT table_name
              FROM information_schema.views ")
   :all-tables (fn [& _] "
                 SELECT s.schema_owner, s.schema_name, t.table_name
                 FROM information_schema.schemata AS s,
                      information_schema.tables AS t
                 WHERE t.table_schema = s.schema_name ")
   :columns (fn [& {:keys [table]}]
              (format "
               SELECT column_name
               FROM information_schema.columns
               WHERE UPPER(table_name) = '%s'
               ORDER BY column_name "
                      (s/upper-case table)))
   :view    (fn [& {:keys [entity-name]}]
              (format "
               SELECT v.view_definition
               FROM information_schema.views AS v
               WHERE UPPER(v.table_name) = '%s' "
                      (s/upper-case entity-name)))})

(def queries
  {
   ;;--------
   :oracle
   ;;--------
   {:entity      (fn [& {:keys [entity-name]}]
                   (format "
                    SELECT text
                    FROM all_source
                    WHERE UPPER(name) = '%s' "
                           (s/upper-case entity-name)))
    :entity-type (fn [& {:keys [entity-name]}]
                   (format "
                    SELECT object_type
                    FROM all_objects
                    WHERE UPPER(OBJECT_NAME) = '%s' "
                    (s/upper-case entity-name)))
    :view        (fn [& {:keys [entity-name]}]
                   (format "
                    SELECT DBMS_METADATA.GET_DDL('VIEW', '%s') FROM DUAL"
                    (s/upper-case entity-name)))
    :types       (fn [& _] "
                    SELECT * FROM USER_TYPES ")
    :owners      (fn [& _] "
                    SELECT DISTINCT(owner)
                    FROM ALL_OBJECTS
                    ORDER BY owner ")
    :tables      (fn [& {:keys [owner]}]
                   (format "
                    SELECT table_name, owner
                    FROM all_tables
                    %s
                    ORDER BY table_name "
                           (if owner
                             (format " WHERE UPPER(owner) = '%s' "
                                     (s/upper-case owner))
                             "")))
    :views       (fn [& _] "
                    SELECT view_name
                    FROM all_views
                    ORDER BY view_name ")
    :all-tables  (fn [& _] "
                    SELECT owner, table_name
                    FROM all_tables
                    WHERE owner NOT IN ('SYS', 'SYSTEM')
                    ORDER BY owner ")
    :columns     (fn [& {:keys [table]}]
                   (format "
                    SELECT column_name
                    FROM ALL_TAB_COLUMNS
                    WHERE UPPER(table_name) = '%s' "
                           (s/upper-case table)))
    :constraints (fn [& {:keys [owner table]}]
                   (cond
                     (and owner table)
                     (format "
                      SELECT * FROM all_constraints
                      WHERE owner = '%s'
                        AND table_name = '%s' "
                             owner table)
                     table
                     (format "
                      SELECT * FROM all_constraints
                      WHERE table_name = '%s' " table)
                     :else
                     "SELECT * FROM user_constraints"))
    :procedures  (fn [& {:keys [owner]}]
                   (format "
                    SELECT object_name, procedure_name
                    FROM all_procedures
                    %s " (if owner
                           (format "WHERE owner = '%s' " owner)
                           "")))
    :procedure (fn [& {:keys [entity-name]}]
                 ;; entity-name is a package name here
                 (format "
                  SELECT text FROM all_source
                  WHERE UPPER(name) = '%s'
                    AND type = 'PACKAGE BODY'
                  ORDER BY line " (s/upper-case entity-name)))
    :objects   (fn [& _] "
                 SELECT * FROM all_objects
                 WHERE object_type IN ('FUNCTION','PROCEDURE','PACKAGE') ")
    :keywords  (fn [& _] "
                 SELECT * FROM V$RESERVED_WORDS ORDER BY keyword ")}
   ;;--------
   :informix
   ;;--------
   {:owners nil
    :tables  (fn [& _]
               (str " SELECT TRIM(t.tabname) as tablesList \n"
                    " FROM systables AS t                  \n"
                    " WHERE t.tabtype = 'T'                \n"
                    "   AND t.tabid >= 100                 \n"
                    " ORDER BY t.tabname;                  \n"))
    :columns (fn [& {:keys [table]}]
               (str " SELECT TRIM(c.colname) AS column_name \n"
                    "  FROM systables AS t, syscolumns AS c \n"
                    " WHERE t.tabid = c.tabid               \n"
                    "   AND t.tabtype = 'T'                 \n"
                    "   AND t.tabid >= 100                  \n"
                    "   AND TRIM(t.tabname) = '" table "'   \n"
                    " ORDER BY c.colno;                     \n"))}
   ;;-------
   :mysql
   ;;-------
   {:owners  (fn [& _]
              (str " SELECT schema_name               \n"
                   " FROM information_schema.schemata \n"))
    :tables  (default-queries :tables)
    :all-tables (fn [& _]
                  (str "SELECT s.schema_name, t.table_name     \n"
                       "FROM information_schema.schemata AS s, \n"
                       "     information_schema.tables AS t    \n"
                       "WHERE t.table_schema = s.schema_name   \n"))
    :columns (default-queries :columns)
    :keywords (fn [& _]
                "SELECT name FROM mysql.help_keyword")}
   ;;--------
   :h2
   ;;--------
   {:tables  (fn [& _] ((default-queries :tables) :schema "PUBLIC"))
    :views   (default-queries :views)
    :all-tables (fn [& _] "
                 SELECT s.schema_owner, s.schema_name, t.table_name
                 FROM information_schema.schemata AS s,
                      information_schema.tables AS t
                 WHERE t.table_schema = s.schema_name
                   AND LCASE(s.schema_name) != 'information_schema' ")
    :columns (default-queries :columns)
    :keywords (fn [& _]
                "SELECT topic FROM information_schema.help")
    :entity-type (fn [& {:keys [entity-name]}]
                   (format "
                    SELECT t.table_type
                    FROM information_schema.tables AS t
                    WHERE UPPER(t.table_name) = '%s' "
                    (s/upper-case entity-name)))
    :view    (fn [& {:keys [entity-name]}]
               ((default-queries :view) :entity-name entity-name))}
   ;;--------
   :sqlite
   ;;--------
   {:tables     (fn [& _] "SELECT name FROM sqlite_master WHERE type='table'")
    :views      (fn [& _] "SELECT name FROM sqlite_master WHERE type='view'")
    :all-tables (fn [& _] "SELECT name FROM sqlite_master WHERE type='table'")
    :columns    (fn [& {:keys [table]}]
                  (str "SELECT p.name as columnName                   \n"
                       "FROM sqlite_master m                          \n"
                       "LEFT OUTER JOIN pragma_table_info((m.name)) p \n"
                       "     ON m.name <> p.name                      \n"
                       "WHERE m.name = '" table "'                    \n"
                       "ORDER BY columnName                           \n"))
    :keywords    (:sqlite k/keywords)
    :entity-type (fn [& {:keys [entity-name]}]
                   (format "
                    SELECT type FROM sqlite_master
                    WHERE UPPER(name) = '%s' "
                           (s/upper-case entity-name)))
    :view        (fn [& {:keys [entity-name]}]
                   (format "
                    SELECT sql FROM sqlite_master
                    WHERE UPPER(name) = '%s' "
                    (s/upper-case entity-name)))}
   ;;-------
   :sqlserver ; ms sql server
   ;;-------
   {:owners  (default-queries :owners)
    :schemas (default-queries :schemas)
    :tables  (default-queries :tables)
    :all-tables (default-queries :all-tables)
    :columns (default-queries :columns)
    :constraints (fn [& {:keys [table]}]
                   (str "SELECT type_desc AS constraint_type, \n"
                        "       name                          \n"
                        "FROM sys.objects                     \n"
                        "WHERE type_desc LIKE '%CONSTRAINT'   \n"
                        "  AND OBJECT_NAME(parent_object_id)='" table "'"))
    :entity      (fn [& {:keys [db entity-name]}]
                   (if (> (get-ms-sql-server-version db) 2000)
                     (str
                      "SELECT definition                                   \n"
                      "FROM sys.objects     o                              \n"
                      "JOIN sys.sql_modules m ON m.object_id = o.object_id \n"
                      "WHERE o.object_id = object_id('" entity-name "')    \n")
                     (str
                      "SELECT c.text                     \n"
                      "FROM sysobjects  o                \n"
                      "JOIN syscomments c ON c.id = o.id \n"
                      "WHERE o.name = '" entity-name "'  \n")))}
   ;;-------
   :postgresql
   ;;-------
   {:owners  (default-queries :owners)
    :tables  (default-queries :tables)
    :all-tables (default-queries :all-tables)
    :columns (default-queries :columns)
    :keywords (fn [& _]
                "SELECT word FROM pg_get_keywords()")}})

(defn autocomplete-available-for-db? [db-type]
  (queries db-type))

(defn get-db-name [db]
  (let [{:keys [database subname connection-uri dbname]} db]
    (or dbname
        database
        (if subname
          (let [separator (if (= (first (.split subname "/")) subname)
                            ":" "/")
                raw-db-name (last (.split subname separator))
                raw-db-name (first (.split raw-db-name "\\?"))
                raw-db-name (first (.split raw-db-name ";"))]
            raw-db-name)
          ;; No subname - parse connection-uri
          (let [props-list (.split connection-uri ";")
                db-name-prop (first
                              (filter
                               (fn [prop]
                                 (=
                                  "databasename"
                                  (.toLowerCase (first (.split prop "=")))))
                               props-list))]
            (if db-name-prop
              ;; "databaseName=my_db_name;"
              (second (.split db-name-prop "="))
              ;; "jdbc:jtds:sqlserver://localhost:1433/my_db_name;"
              (last (.split (first props-list) "/"))))))))

(defn get-user [db]
  (or
   (:user db)
   (let [{:keys [user connection-uri]} db]
     (or user
         (if connection-uri
           (second (.split
                    (let [props-list (.split connection-uri ";")]
                      (first
                       (filter
                        (fn [prop]
                          (=
                           "user"
                           (.toLowerCase (first (.split prop "=")))))
                        props-list))) "="))
           :undefined-user)))))

(def product-assoc {"oracle:sid" "oracle"})

(defn get-db-type [db]
  (let [{:keys [subprotocol connection-uri dbtype]} db
        result (or dbtype
                   subprotocol
                   (let [attrs (.split connection-uri ":")]
                     (if (= (second attrs) "jtds")
                       ;; jdbc:jtds:sqlserver://...
                       (nth attrs 2)
                       ;; jdbc:sqlserver://localhost\instance:1433;
                       (second attrs))))]
    (keyword (or (product-assoc result) result))))

(defn- get? [obj & [force?]]
  (if (and obj (or force? (realized? obj)))
    @obj))

(defn get-this-owner [db & [owner]]
  "Return current owner/schema."
  (or owner
      (get? (get-in @cache [db :this-owner]) false)
      (do
        (swap! cache assoc-in [db :this-owner]
               (future
                 ((fn [db]
                    (let [db-type (get-db-type db)]
                      (case db-type
                        :sqlserver (if (> (get-ms-sql-server-version db) 2000)
                                     ;; Get default SQL Server schema for session
                                     (db->value db "SELECT SCHEMA_NAME()")
                                     "dbo")
                        :oracle (db->value
                                 db
                                 "SELECT sys_context('userenv', 'current_schema')
                                  FROM dual")
                        ;; By default
                        ;; Assume owner == schema (it can be wrong in general).
                        (get-user db)))) db)))
        (get? (get-in @cache [db :this-owner]) true))))

(defn select-db-meta-script [db meta-type &
                             {:keys [owner
                                     table
                                     entity-name]}]
  "Return SQL request to obtain some database structure info."
  (let [db-type (get-db-type db)
        meta-type (if (keyword? meta-type) meta-type (keyword meta-type))
        ;; owner (get-this-owner db owner)
        sql-receiver (get-in queries [db-type meta-type])
        sql (if sql-receiver
              (sql-receiver :db db
                            :owner owner
                            :schema owner
                            :table table
                            :entity-name entity-name
                            :db-name (get-db-name db)))]
    sql))

(defn get-namespace [db]
  "Find tables namespace for current database type.
Possible cases for namespace:
* schema is sutable (or both schema and owner)
  for this DB type - use `:schemas`
* only owner is sutable, so use `:owners`
* none of them is sutable - `nil`"
  (let [db-type (get-db-type db)]
   (cond
     (get-in queries [db-type :schemas]) :schemas
     (get-in queries [db-type :owners]) :owners
     :else nil)))

(defn get-owners [db & [force?]]
  "Return owners list from cache if already received from DB,
check if receiveing process is not running, then start it."
  (let [namespace (get-namespace db)]
    (if namespace
      (do
        (if (not (get-in @cache [db namespace]))
          (swap! cache assoc-in [db namespace]
                 (future ((fn [db]
                            (let [sql (select-db-meta-script
                                       db namespace)]
                              (sort (db->column db sql)))) db))))
        (get? (get-in @cache [db namespace]) force?))
      (list))))

(defn get-tables [db & [owner_ force?]]
  "Return tables list for this owner from cache if already received from DB,
check if receiveing process is not running, then start it."
  (let [owner (get-this-owner db owner_)]
    (if (not (get->in @cache [db :tables owner]))
      (swap! cache assoc-in [db :tables owner]
             (future ((fn [db owner]
                        (let [sql (select-db-meta-script db :tables
                                                         :owner owner)]
                          (db->column db sql)))
                      db owner))))
    (if owner_
      (get? (get->in @cache [db :tables owner]) force?)
      (do (if (not (get-in @cache [db :all-tables]))
            (swap! cache assoc-in [db :all-tables]
                   (future ((fn [db]
                              (let [sql (select-db-meta-script
                                         db :all-tables)]
                                (sort (db->>column db sql))))
                            db))))
          (get? (get-in @cache [db :all-tables]) force?)))))

(defn get-views [db & [force?]]
  "Return views list from cache if already received from DB,
check if receiveing process is not running, then start it."
  (if (not (get-in @cache [db :views]))
    (swap! cache assoc-in [db :views]
           (future ((fn [db]
                      (let [sql (select-db-meta-script db :views)]
                        (sort (db->column db sql)))) db))))
  (get? (get-in @cache [db :views]) force?))

(defn get-colomns [db table force?]
  "Return colomns list for this table from cache if already received from DB,
check if receiveing process is not running, then start it."
  (if (not (get->in @cache [db :colomns table]))
    (swap! cache assoc-in [db :colomns table]
           (future ((fn [db table]
                      (let [sql (select-db-meta-script db :columns
                                                       :table table)]
                        (sort (db->column db sql))))
                    db table))))
  (get? (get->in @cache [db :colomns table])
        force?))

(defn get-keywords [db force?]
  "Return keywords list for this database type from cache if already received
from DB, check if receiveing process is not running, then start it."
  (if-let [keywords-query (get-in queries [(get-db-type db) :keywords])]
    (if (not (get->in @cache [db :keywords]))
      (swap! cache assoc-in [db :keywords]
             (if (vector? keywords-query)
               (future (identity keywords-query))
               (future ((fn [db]
                          (let [sql (select-db-meta-script db :keywords)]
                            (sort
                             (filter #(not (nil? %))
                                     (db->column db sql)))))
                        db))))))
  (get? (get->in @cache [db :keywords]) force?))

(defn is-owner? [db prefix]
  (in? (get-owners db) prefix :case-sensitive false))

(defn autocomplete-loading []
  "Not loaded tables yet. Output pending db structure data..."
  (list "t"))

(defn autocomplete-result [result]
  "Output autocomplete result."
  (cons "nil" result))

(defn autocomplete-nothing []
  "Output autocomplete nothing."
  (list "nil"))

(defn get-owners-candidates [db sql prefix-1 & _]
  "Return owners candidates autocomplete list from the database structure
cache, async request to fill it, if not yet.
The result list has the following structure:
(pending item1 item2 ...)
If `pending` is t - the async request to get the structure is running
if `pending` is nil - no request is running, return result immediately."
  (let [owners (get-owners db)]
    (if prefix-1
      ;; Assume schema|table.#<schemas-list> is not applicable.
      ;; So, return empty list.
      (autocomplete-nothing)
      (if owners
        (autocomplete-result owners)
        ;; pending owners...
        (autocomplete-loading)))))

(defn get-tables-candidates [db sql prefix-1 & _]
  "Return tables candidates autocomplete list."
  (let [tables (if (not prefix-1)
                 ;; something#
                 (get-tables db)
                 (if (is-owner? db prefix-1)
                   ;; [owner].#<tables-list>
                   (get-tables db prefix-1)
                   ;; owners still pending or
                   ;; unknown?.# case
                   (list)))]
    (if tables
      (autocomplete-result tables)
      ;; pending tables...
      (autocomplete-loading))))

(defn get-views-candidates [db sql & _]
  "Return views candidates autocomplete list."
  (if-let [views (get-views db)]
    (autocomplete-result views)
    ;; pending views...
    (autocomplete-loading)))

(defn match-alias? [sql owner table probable-alias]
  "Check if prefix (`probable-alias`) is alias for `table` in this `sql`."
  (let [sql (s/lower-case sql)
        owner (if owner
                (str (s/lower-case owner) "\\.")
                "\\s+(\\w+\\.)?")
        table (s/lower-case table)
        probable-alias (s/lower-case probable-alias)
        alias-pattern (re-pattern
                       (str owner table "(\\s+as)?"
                            "\\s+" probable-alias))]
    (not (nil? (re-find alias-pattern sql)))))

(defn get-all-tables [db]
  "Get all tables for all owners/schemas."
  (flatten
   (mapv #(get-tables db % true)
         (get-owners db true))))

(def insert-re
  (re-pattern (str "(?i)\\s*INSERT\\s+INTO\\s+(\\S+)\\s+")))

(defn insert-sql? [sql]
  "If `sql` is INSERT query, return a table name used to insert new records.
Otherwise return nil."
  (second (re-find insert-re sql)))

(def update-re
  (re-pattern (str "(?i)\\s*UPDATE\\s(\\S+)\\s+\n*SET.*")))

(defn update-sql? [sql]
  "If `sql` is UPDATE query, return a table name used to modify the existing
records. Otherwise return nil."
  (second (re-find update-re sql)))

(defn get-colomns-candidates [db sql prefix-1 & [prefix-2]]
  "Return colomns candidates autocomplete list."
  ;; Possible cases:
  ;; 1. [owner|schema.]table.#<colomns-list>
  ;; 2. SELECT alias.#<colomns-list> FROM [owner|schema.]table AS alias
  ;; 3. SELECT complex-alias.#<colomns-list> FROM (SELECT...) AS complex-alias
  ;; 4. INSERT INTO table (field1, .#) values (123, 'text')
  ;; 5. UPDATE table SET field1 = 123, .# = 'text' WHERE id = 1
  ;;
  ;; In any case, consider `prefix-1` as table or alias
  ;; and optional `prefix-2` as owner or schema.
  ;; When no any prefix at all, check for 4 (INSERT) or 5 (UPDATE) case.
  (let [owner prefix-2
        tables-list (concat
                     (if owner
                       (get-tables db owner)
                       ;; In case of queries like
                       ;; "SELECT u.# FROM custom.Users as u"
                       ;; when "custom" is not the current
                       ;; schema, the full DB structure tree
                       ;; sould be built to obtain overall
                       ;; tables list for all owners/schemas.
                       (if (get-namespace db)
                         ;; Database has namespaces
                         (if (not (get-owners db))
                           ;; Not received owners list yet -
                           ;; pending...
                           (do
                             (future (get-all-tables db))
                             nil)
                           ;; Owners list is already here -
                           ;; force tables list receiving!
                           (get-all-tables db))
                         ;; Database hasn't namespaces -
                         ;; no owners needed
                         (get-tables db)))
                     (get-views db))]
    (if (not (and tables-list
                  (not-empty (filter not-empty tables-list))))
      ;; no tables yet
      ;; pending tables...
      (autocomplete-loading)
      ;; Tables list loaded:
      (if (not prefix-1)
        (let [insert-or-update-table (or (insert-sql? sql)
                                         (update-sql? sql))]
          (cond
            (and (not-empty insert-or-update-table)
                 (in? tables-list insert-or-update-table
                      :case-sensitive false))
            ;; INSERT INTO table (field1, .#) values (123, 'text')
            ;; or
            ;; UPDATE table SET field1 = 123, .# = 'text' WHERE id = 1
            (autocomplete-result
             (get-colomns db insert-or-update-table true))
            ;; SELECT or other queries types
            :else (autocomplete-nothing)))
        (if (in? tables-list prefix-1 :case-sensitive false)
          ;; table.#<colomns-list>
          (let [table prefix-1
                ;; force columns-cache obtaining...
                columns-list (get-colomns db table true)]
            ;; ok - columns
            (autocomplete-result columns-list))
          (let [sql (c/clean-sql sql)
                table-alias (first
                             (filter
                              (fn [table]
                                (match-alias? sql owner table prefix-1))
                              tables-list))]
            (if table-alias
              ;; table-alias.#<colomns-list>
              (autocomplete-result (get-colomns db table-alias true))
              ;; Check "SELECT t.# FROM (SELECT ... ) AS t" case.
              (let [pattern (re-pattern (str "\\(.+\\)(\\s|\\s(as)\\s)"
                                             prefix-1))
                    complex-alias (first (re-find pattern sql))
                    complex-alias (if complex-alias
                                    (subs complex-alias 1
                                          (.lastIndexOf complex-alias ")")))]
                (if complex-alias
                  (let [{:keys [success result]} (c/query-meta db complex-alias)]
                    (if success
                      ;; complex-alias.#<colomns-list>
                      (autocomplete-result (mapv :name result))
                      ;; Can't execute "blah blah" to get metadata in
                      ;; "SELECT t.# FROM (blah blah) AS t" case.
                      (autocomplete-nothing)))
                  ;; unknown?.# case
                  ;; nothing to complete
                  (autocomplete-nothing))))))))))

(defn get-entity-type [db entity-name]
  "Determine DB entity type, whether id `:view`, `:type` or `:procedure`."
  (if-let [sql (select-db-meta-script db :entity-type
                                      :entity-name entity-name)]
    (if-let [found-type (db->value db sql)]
      (-> found-type
          s/lower-case
          keyword))))

(defn get-entity-description [db entity-name]
  "Get DB entity or view creation SQL."
  (if-let [type (get-entity-type db entity-name)]
    (if-let [entity-obtainig-sql (select-db-meta-script
                                  db
                                  type
                                  :entity-name entity-name)]
      (if-let [entity-sql (db->value
                           db
                           entity-obtainig-sql
                           :row-fn (fn [row]
                                     (mapv #(if (is-clob? %)
                                              (clob-to-string %)
                                              %)
                                           row)))]
        (c/complete (o/format-sql-if-required entity-sql)
                    :mode 'sql-mode)
        (c/complete (format "Can't find %s named %s."
                            (name type) entity-name)))
      (c/complete (format (str "Script for obtaining DB entity of type %s "
                               "was not added for this database type.")
                          (name type))))
    (c/complete (format "Can't determine type of %s." entity-name))))

(defn get-cache []
  "Output actual cache."
  @cache)

(defn invalidate-cache [db]
  "Clean your current connection cache (database owners and tables list)."
  (swap! cache assoc-in [db] nil))
