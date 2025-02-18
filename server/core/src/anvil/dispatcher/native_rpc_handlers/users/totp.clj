(ns anvil.dispatcher.native-rpc-handlers.users.totp
  (:use slingshot.slingshot)
  (:require [one-time.core :as ot]
            [anvil.runtime.secrets :as secrets]
            [crypto.random :as random]
            [anvil.dispatcher.native-rpc-handlers.util :as util]
            [one-time.qrgen :as ot-qr]
            [anvil.dispatcher.native-rpc-handlers.users.util :refer [get-props-with-named-user-table
                                                                     get-user-row-by-id
                                                                     row-to-map]]
            [anvil.dispatcher.core :as dispatcher])
  (:import (anvil.dispatcher.types BlobMedia)
           (java.util Date)))

(defn generate-totp-secret [_kwargs email]
  (let [email (or email
                  (binding [util/*client-request?* false]
                    (let [{:keys [user_table]} (get-props-with-named-user-table)
                          v1-row-id-str (or (get-in @util/*session-state* [:users :logged-in-id]) (get-in @util/*session-state* [:users :mfa-reset-user-id]))
                          user-row (get-user-row-by-id user_table v1-row-id-str)]
                      (get (row-to-map user-row) "email")))
                  (throw+ {:anvil/server-error "Email address not provided and could not be inferred"}))
        secret (ot/generate-secret-key)]
    {:secret     secret
     :qr_code    (BlobMedia. "image/png" (.toByteArray (ot-qr/totp-stream {:image-type :PNG
                                                                           :image-size 250
                                                                           :label      (:name util/*app*)
                                                                           :user       email
                                                                           :secret     secret})) "qr-code.png")
     :mfa_method {:type "totp" :id (random/base32 5) :serial 1 :secret (secrets/encrypt-str-with-global-key :u secret)}}))

(defn validate-totp-code [_kwargs mfa-method code]
  (let [secret (secrets/decrypt-str-with-global-key :u (:secret mfa-method))
        t (System/currentTimeMillis)
        valid? #(ot/is-valid-totp-token? (try (Integer/parseInt code) (catch Exception _ 0)) secret {:date (Date. ^long %)})]
    (some valid? [t (+ t 30000) (- t 30000)])))

(swap! dispatcher/native-rpc-handlers merge
       {"anvil.private.users.totp.generate_secret" (util/wrap-native-fn generate-totp-secret)
        "anvil.private.users.totp.validate_code"   (util/wrap-native-fn validate-totp-code)})