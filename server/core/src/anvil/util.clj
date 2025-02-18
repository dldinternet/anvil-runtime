(ns anvil.util
  (:use [clojure.pprint]
        [slingshot.slingshot])
  (:require [digest]
            [anvil.runtime.conf :as conf]
            [clojure.data.json :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.tools.logging :as log]
            [ring.util.codec :as codec]
            [clojure.string :as string]
            [anvil.metrics :as metrics]
            [clojure.core.cache.wrapped :as cache]
            [org.httpkit.client :as http]
            [org.httpkit.sni-client :as sni]
            [org.httpkit.sni-client :as https]
            [compojure.core]
            [clojure.java.io :as io])
  (:import (java.sql SQLException)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)
           (org.mindrot.jbcrypt BCrypt)
           (clojure.lang Keyword)
           (java.net InetAddress)
           (com.maxmind.db CHMCache)
           (com.maxmind.geoip2 DatabaseReader$Builder DatabaseReader)
           (java.io File ByteArrayOutputStream ByteArrayInputStream FileOutputStream)
           (com.maxmind.geoip2.exception GeoIp2Exception)
           (com.mchange.v2.c3p0 DataSources)
           (java.util Properties Map)
           (javax.sql DataSource)
           (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)
           (com.maxmind.geoip2.model CityResponse)
           (org.apache.commons.codec.binary Base64)))

;; This needs to work on lots of different formats.
;; E.g. "16" - TODO: Add tests
(defn get-java-version []
  (let [s (str (System/getProperty "java.version"))
        [_ major _ minor _ update] (re-matches #"(?:1\.)?(\d+)(\.(\d+)([\._](\d+))?)?(?:\.\d+)*" s)]
    [(Integer/parseInt major) (Integer/parseInt (or minor "0")) (Integer/parseInt (or update "0"))]))

(def is-sane-java-version?
  ; Make sure we have this fix: https://bugs.openjdk.java.net/browse/JDK-8243717
  (let [[major _minor update] (get-java-version)]
    (or (> major 8)
        (and (= major 8)
             (> update 261)))))

(alter-var-root #'org.httpkit.client/*default-client* (fn [_] (http/make-client {:ssl-configurer (partial https/ssl-configurer {:hostname-verification? true})})))
(def insecure-client (http/make-client {}))
(def make-ssl-engine (constantly nil))

;; To define hookable functions, in runtime:
;;     (def set-foo-hooks! (hook-setter #{foo bar baz})
;; To set hooks, in platform or standalone:
;;     (runtime.blah/set-foo-hooks! {:foo my-foo, :bar my-bar})
;; OR, if your hooked functions have the same names:
;;     (runtime.blah/set-foo-hooks! (util/hooks #{foo bar baz}))

(defn -set-hooks! [kws-to-vars kws-to-values]
  (doseq [[kw val] kws-to-values]
    (if-let [v (get kws-to-vars kw)]
      (alter-var-root v (constantly val))
      (throw (Exception. (format "%s is not a hookable function" (name kw)))))))

(defn -map-from-symbols
  ([syms] (-map-from-symbols syms identity))
  ([syms default-val-fn]
   (if (map? syms)
     syms
     (into {}
           (for [sym syms]
             (do
               (when-not (symbol? sym)
                 (Exception. (format "'%s' is not a symbol" (pr-str sym))))
               [(keyword (name sym)) (default-val-fn sym)]))))))

(defmacro hook-setter [hookable-fns]
  (let [var-map (-map-from-symbols hookable-fns (fn [sym] `(var ~sym)))]
    `(fn [hook-map#]
       (-set-hooks! ~var-map hook-map#))))

(defmacro hooks [hooks]
  (-map-from-symbols hooks))

(def ^:dynamic *in-repl?* false)

; These apps are down for maintenance. Don't let anything touch them.
(defonce locked-app-ids (atom #{}))

(defn app-locked? [app-id]
  (contains? @locked-app-ids (.toUpperCase (or app-id ""))))

(defn sha-256 [val]
  (digest/sha-256 (or val "")))

(defn bcrypt-checkpw [password hash]
  (try
    (let [hash (clojure.string/replace hash #"^\$2[bxy]\$" "\\$2a\\$")]
      (BCrypt/checkpw password hash))
    (catch Exception e
      ;; E.g. NullPointerException because there was no hash or no password
      false)))

(defn- hexify "Convert byte sequence to hex string" [coll]
  (let [hex [\0 \1 \2 \3 \4 \5 \6 \7 \8 \9 \a \b \c \d \e \f]]
    (letfn [(hexify-byte [b]
              (let [v (bit-and b 0xFF)]
                [(hex (bit-shift-right v 4)) (hex (bit-and v 0x0F))]))]
      (apply str (mapcat hexify-byte coll)))))

(defn hmac-sha-256
  [key-seq byte-seq]
  (let [hmac-key (SecretKeySpec. (byte-array key-seq) "HmacSHA256")
        hmac (doto (Mac/getInstance "HmacSHA256") (.init hmac-key))]
    (hexify (.doFinal hmac (byte-array byte-seq)))))

;; Pass to json/write-str to preserve slashes in keywords
(defn preserve-slashes [val]
  (if (keyword? val)
    (.substring (.toString ^Keyword val) 1)
    val))

(defn write-json-str [val & options]
  (apply json/write-str val :key-fn preserve-slashes options))

(defn read-json-str [val]
  (json/read-str val :key-fn keyword))

(defn as-properties [kw-map]
  (let [p (Properties.)]
    (doseq [[k v] kw-map]
      (.put p (name k) (str v)))
    p))

(defn inputstream->bytes [inputstream]
  (with-open [out (ByteArrayOutputStream.)]
    (io/copy inputstream out)
    (.toByteArray out)))

(defn slurp-binary [^File file]
  (with-open [in (io/input-stream (.getAbsolutePath file))]
    (inputstream->bytes in)))

(defn spit-binary [^File file byte-input]
  (with-open [o (FileOutputStream. (.getAbsolutePath file))]
    (.write o byte-input)))

(def ^:dynamic *metric-query-name* nil)

(defmacro with-metric-query [name & body]
  `(binding [*metric-query-name* (str "<" ~name ">")]
     ~@body))

(defonce with-long-query* (fn [f] (f)))

(defmacro with-long-query [& body]
  `(with-long-query* (fn [] ~@body)))

(defonce get-global-db-datasource
         (fn []
           (when-not conf/db
             (throw (Exception. "Attempted to use util/db before conf/db was set up")))
           (let [{:keys [subprotocol subname dbtype dbname host port connection-uri]
                  :as   db-spec} conf/db
                 pool-params conf/db-pool-params]

             (let [jdbc-uri (or connection-uri
                                (when (and subprotocol subname)
                                  (format "jdbc:%s:%s" subprotocol subname))
                                (when (and dbtype dbname host)
                                  (format "jdbc:%s://%s%s/%s" dbtype host (when port (str ":" port)) dbname)))

                   ds (DataSources/unpooledDataSource jdbc-uri
                                                      (as-properties (-> conf/db
                                                                         (dissoc :connection-uri :subprotocol :subname :dbtype :dbname
                                                                                 :host :port :classname))))

                   pool-params (into {} (for [[k v] pool-params] [(name k) (if (integer? v) (int v) v)]))]
               (DataSources/pooledDataSource ds ^Map pool-params)))))

(def set-db-hooks! (hook-setter [get-global-db-datasource with-long-query*]))

(defonce db {:datasource (let [datasource (delay (get-global-db-datasource))]
                           (reify DataSource
                             (getConnection [_this] (.getConnection ^DataSource @datasource))))})


(defmacro letd [lets & body]
  (let [new-lets (->> lets
                      (partition 2)
                      (map (fn [[s v]] `(~s ~v _# (println (str (quote ~s) ":") ~s))))
                      (apply concat)
                      (vec))
        new-lets (conj new-lets `_# `(println "************"))]
    `(do
       (println "*** letd ***")
       (let ~new-lets ~@body))))

(defmacro log-time [level message & body]
  `(let [start-time# (System/currentTimeMillis)
         val# (do ~@body)]
    (log/logp ~level ~message (str "(" (- (System/currentTimeMillis) start-time#) " ms)"))
    val#))

(def TXN-RETRIES 26)                                        ; Max time ~30s. Probably.

(def total-txn-retries (atom 0))

(defonce rollback-quota-changes! (fn [rollback-state]))

(def set-quota-hooks! (hook-setter [rollback-quota-changes!]))

(defn -with-db-transaction [db-spec relaxed-isolation-level f]
  (loop [n TXN-RETRIES]
    (let [rollback-state (when-not (:level db-spec)
                           (atom {}))

          actual-isolation-level (if (and relaxed-isolation-level (or (zero? (jdbc/get-level db-spec))
                                                                      (= relaxed-isolation-level (:anvil-txn-isolation-level db-spec))))
                                   relaxed-isolation-level
                                   :serializable)
          [recur? r]
          (try+
            (jdbc/with-db-transaction [db-c db-spec {:isolation actual-isolation-level}]
              (let [db-c (if rollback-state (assoc db-c :anvil-quota-rollback-state rollback-state
                                                        :anvil-txn-isolation-level actual-isolation-level) db-c)]
                [false (f db-c)]))
            (catch #(or (and (instance? SQLException %) (#{"40001" "40P01"} (.getSQLState %)))
                        (and (:anvil/server-error %) (= (:type %) "anvil.tables.TransactionConflict")))
                   e
              (when rollback-state
                (rollback-quota-changes! rollback-state))
              (if (and (> n 0) (not (:level db-spec)))
                (do
                  (log/trace (str "Conflict in with-db-transaction: " e))
                  (Thread/sleep (long (* (Math/random) (Math/pow 2 (* 0.5 (- TXN-RETRIES n))))))
                  [true nil])
                (throw+ e)))
            (catch Throwable e
              (when rollback-state
                (rollback-quota-changes! rollback-state))
              (throw e)))]
      (if recur?
        (do
          (swap! total-txn-retries inc)
          (recur (dec n)))
        r))))

(defmacro with-db-transaction [[conn-sym spec isolation-level] & body]
  `(-with-db-transaction ~spec ~isolation-level (fn [db#]
                                                  (let [~conn-sym db#]
                                                    ~@body))))

(defn -with-try-db-lock [lock-name f]
  (let [lock-number (.hashCode (str lock-name))]
    (jdbc/with-db-transaction [db-t db] ;; Note that we don't actually use this txn connection for anything except locking.
      (if (:locked (first (jdbc/query db-t ["SELECT pg_try_advisory_xact_lock(?::bigint) as locked;" lock-number])))
        (f)
        (throw+ {:anvil/lock-unavailable lock-name})))))

(defmacro with-try-db-lock [lock-name & body]
  `(-with-try-db-lock ~lock-name (fn [] ~@body)))

(def ^:dynamic *db-locks* {})

(defn -with-db-lock [lock-name shared? f]
  (if-let [lock (get *db-locks* lock-name)]
    (if (or shared? (not (:shared? lock)))
      (f)
      (throw (Exception. (str "Tried to upgrade from a shared to an exclusive lock for " lock-name))))
    (let [lock-number (.hashCode (str lock-name))]
      (jdbc/with-db-transaction [db-t db]                   ;; Note that we don't actually use this txn connection for anything except locking.
        (jdbc/query db-t [(if shared? "SELECT pg_advisory_xact_lock_shared(?::bigint)" "SELECT pg_advisory_xact_lock(?::bigint)")
                          lock-number])
        (binding [*db-locks* (assoc *db-locks* lock-name {:shared? true})]
          (f))))))

(defmacro with-db-lock [lock-name shared? & body]
  `(-with-db-lock ~lock-name (boolean ~shared?) (fn [] ~@body)))

(defn- double-escape [^String x]
  (.replace (.replace x "\\" "\\\\") "$" "\\$"))

(defn real-actual-genuine-url-encoder [unencoded & [encoding]]
  (string/replace
    unencoded
    #"[^A-Za-z0-9_~.-]+"
    #(double-escape (codec/percent-encode % (or encoding "UTF-8")))))

(defmacro or-str
  ([] nil)
  ([s & rest]
   `(let [s# ~s]
      (if (and s#
               (not-empty s#))
        s#
        (or-str ~@rest)))))

(defmacro timeit [desc [checkpoint!] & body]
  `(let [s# (System/currentTimeMillis)
         ~checkpoint! (fn [c#] (log/trace (str ~desc ":" c#) "completed in" (- (System/currentTimeMillis) s#) "ms"))
         r# (do ~@body)]
     (log/trace ~desc "completed in" (- (System/currentTimeMillis) s#) "ms")
     r#))

(def additional-mime-types
  {"woff2" "application/font-woff2"})

(defn iso-instant [^Instant instant]
  (.format (.withZone (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss'Z'")
                      (ZoneOffset/UTC))
           instant))

(defn iso-now [] (iso-instant (Instant/now)))

(def geoip-db
  (delay
    (when conf/geoip-db-path
      (let [f (File. ^String conf/geoip-db-path)]
        (when (.isFile f)
          (-> (DatabaseReader$Builder. f)
              (.withCache (CHMCache.))
              (.build)))))))

(defn get-ip-location [remote-addr]
  (when (and @geoip-db remote-addr)
    (try
      (let [r ^CityResponse
              (->> (InetAddress/getByName remote-addr)
                   (.city ^DatabaseReader @geoip-db))]

        {:country     (when-let [c (.getCountry r)]
                        {:code (.getIsoCode c), :name (.getName c)})
         :city        (when-let [c (.getCity r)] (.getName c))
         :location    (when-let [l (.getLocation r)] {:lat (.getLatitude l), :lng (.getLongitude l)})
         :subdivision (when-let [s (.getMostSpecificSubdivision r)]
                        {:code (.getIsoCode s), :name (.getName s)})})
      (catch GeoIp2Exception _e
        nil))))

(defn report-uncaught-exception [location-description e]
  (log/error e (str "Reporting uncaught exception in " location-description))
  (metrics/inc! :api/uncaught-exceptions-total))

(defmacro with-report-uncaught-exceptions [name & body]
  ;; NB: This re-throws any exceptions, so make sure they don't end up being caught by report-uncaught-exception again.
  `(try
     ~@body
     (catch Exception e#
       (report-uncaught-exception ~name e#)
       (throw e#))))

(defmacro with-meta-when [m & body]
  `(when-let [r# (do ~@body)]
     (with-meta r# ~m)))

(defn vary-meta-when [val fn & args]
  (when val
    (apply vary-meta val fn args)))

(defmacro $ [a b c] `(~b ~a ~c))

(defmacro defn-with-ttl-memoization [func args ttl & body]
  `(def ~func
     (let [ttl-cache# (cache/ttl-cache-factory {} :ttl ~ttl)]
       (fn ~args
         (-> (cache/through-cache ttl-cache# ~args (fn [~args] ~@body))
             (get ~args))))))

(defn dissoc-in-or-remove [map [key & more-keys]]
  (if (nil? key)
    nil
    (let [r (dissoc-in-or-remove (get map key) more-keys)]
      (if (empty? r)
        (dissoc map key)
        (assoc map key r)))))

(defn disj-in-or-remove [map-or-set [key & more-keys] element]
  (if (nil? key)
    (disj map-or-set element)
    (let [r (disj-in-or-remove (get map-or-set key) more-keys element)]
      (if (empty? r)
        (dissoc map-or-set key)
        (assoc map-or-set key r)))))

(defmacro debug-or
  ([] `(do (println "DROPTHROUGH" (Thread/currentThread)) nil))
  ([v & more] `(if-let [v# ~v] (do (println ~(pr-str v) (Thread/currentThread)) v#) (debug-or ~@more))))

(defmacro timer-task [error-context & body]
  `(proxy [java.util.TimerTask] []
     (run []
       (try
         ~@body
         (catch Exception e#
           (log/error e# "TimerTask error" ~error-context))))))

(in-ns 'compojure.core)
(let [old-context-request context-request]
  (defn- context-request [request route]
    (when-let [old-req (old-context-request request route)]
      (assoc old-req :context-paths (conj (or (:context-paths request) []) (remove-suffix (:source route) ":__path-info"))))))
(in-ns 'anvil.util)

(defn wait-for [pred timeout check-ms]
  (let [timeout-time (+ (System/currentTimeMillis) timeout)]
    (while (and (not (pred))
               (< (System/currentTimeMillis) timeout-time))
      (Thread/sleep check-ms)))
  (pred))

(defn printlog [& more]
  (let [s (with-out-str (apply print more))]
    (log/info s)
    (when *in-repl?*
      (println s))))

(defn basic-auth-header [user pass]
  (str "Basic " (String. ^bytes (Base64/encodeBase64 (.getBytes (str user ":" pass))))))

;;;;;;;;;;;;;;;;;;;
;; The following two functions are corrected versions of the ones in ring.middleware.proxy-headers, which
;; incorrectly take the LAST value in the X-Forwarded-For header.
(defn forwarded-remote-addr-request
  "Change the :remote-addr key of the request map to the FIRST value present in
  the X-Forwarded-For header. See: wrap-forwarded-remote-addr."
  [request]
  (if-let [forwarded-for (get-in request [:headers "x-forwarded-for"])]
    (let [remote-addr (clojure.string/trim (re-find #"^[^,]*" forwarded-for))]
      (assoc request :remote-addr remote-addr))
    request))

(defn wrap-correct-forwarded-remote-addr
  "Middleware that changes the :remote-addr of the request map to the
  FIRST value present in the X-Forwarded-For header."
  [handler]
  (fn
    ([request]
     (handler (forwarded-remote-addr-request request)))
    ([request respond raise]
     (handler (forwarded-remote-addr-request request) respond raise))))

;;;;;;;;;;;;;;;;;;;;;; End middleware fix

(defmacro with-temp-file [[f initial-content] & body]
  `(let [~f (File/createTempFile "temp" "")
         initial-content# ~initial-content]
     (cond
       (string? initial-content#)
       (spit ~f initial-content#)

       (bytes? initial-content#)
       (spit-binary ~f initial-content#))
     (try
       ~@body
       (finally
         (.delete ~f)))))

(defn as-int [x]
  (if (integer? x)
    x
    (try
      (Integer/parseInt x)
      (catch NumberFormatException _
        nil))))