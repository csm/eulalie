(ns eulalie.creds
  (:require [eulalie.util :as util]
            [eulalie.instance-data :as instance-data]
            [eulalie.platform.time :as platform.time]
            [glossop.core :as g
             #? (:clj :refer :cljs :refer-macros) [go-catching <?]]))

(defn env []
  (let [secret-key (util/env! "AWS_SECRET_ACCESS_KEY")
        token      (util/env! "AWS_SESSION_TOKEN")]
    (when (not-empty secret-key)
      (cond->
          {:access-key (util/env! "AWS_ACCESS_KEY_ID")
           :secret-key secret-key}
        token (assoc :token token)))))

(defmulti creds->credentials
  "Unfortunately-named mechanism to turn the informally-specified 'creds' map
  supplied by the user into a map with :access-key, :secret-key, :token members,
  suitable for signing requests, etc.  To support more exotic use-cases, like
  mutable/refreshable credentials, we offer this layer of indirection."
  :eulalie/type)
(defmethod creds->credentials :default [creds]
  (go-catching creds))

(defmethod creds->credentials :mutable [{:keys [current]}]
  (go-catching @current))

(defn refresh! [{:keys [current refresh] :as creds}]
  (go-catching
    ;; The no expiry junk is kind of awkward, but we don't want (iam) to do any
    ;; immediate I/O, so we just assume the credentials will expire
    (reset! current
            (-> (refresh)
                <?
                (update :expiration #(or % ::no-expiry))))
    creds))
#? (:clj (def refresh!! (comp g/<?! refresh!)))

(defmethod creds->credentials :expiring
  [{:keys [threshold current refresh] :as m} & [msecs-now]]
  (go-catching
    (let [{:keys [expiration]} @current]
      (when (or (nil? expiration)
                (<= (- expiration (or msecs-now (platform.time/msecs-now))) threshold))
        ;; So this is pretty wasteful - there could be large numbers of
        ;; concurrent requests, all with the same expired credentials - they
        ;; should all be waiting on a single request
        (<? (refresh! m)))
      @current)))

(defn expiring-creds
  [refresh-fn & [{:keys [threshold]
                  :or {threshold (* 60 1000 5)}}]]
  {:eulalie/type :expiring
   :current (atom nil)
   :refresh refresh-fn
   :threshold threshold})

(defn iam
  ([]
   (expiring-creds instance-data/default-iam-credentials!))
  ([role]
   (expiring-creds #(instance-data/iam-credentials! role))))
