(ns auth.core
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(def ^:private secret-key "super-secret-key-change-in-prod")
(def ^:private default-ttl 3600) ;; 1 hour

(defn- base64-encode [^String s]
  (.encodeToString (java.util.Base64/getUrlEncoder) (.getBytes s "UTF-8")))

(defn- base64-decode [^String s]
  (String. (.decode (java.util.Base64/getUrlDecoder) s) "UTF-8"))

(defn- hmac-sha256 [data key]
  (let [mac (javax.crypto.Mac/getInstance "HmacSHA256")
        secret-key-spec (javax.crypto.spec.SecretKeySpec. (.getBytes key "UTF-8") "HmacSHA256")]
    (.init mac secret-key-spec)
    (.encodeToString (java.util.Base64/getUrlEncoder) (.doFinal mac (.getBytes data "UTF-8")))))

(defn- normalize-role [role]
  (cond
    (keyword? role) (name role)
    (string? role) role
    :else nil))

(defn generate-token
  "Generate a JWT token for the given user map.
   Options: :ttl seconds until expiry (default 3600)"
  ([user] (generate-token user {}))
  ([user opts]
   (let [header (base64-encode (json/write-str {:alg "HS256" :typ "JWT"}))
         now (quot (System/currentTimeMillis) 1000)
         ttl (get opts :ttl default-ttl)
         subject (or (:id user) (:sub user))
         claims {:sub subject
                 :email (:email user)
                 :role (normalize-role (:role user))
                 :iat now
                 :exp (+ now ttl)}
         payload (base64-encode (json/write-str claims))
         signature (hmac-sha256 (str header "." payload) secret-key)]
     (str header "." payload "." signature))))

(defn decode-token
  "Decode a JWT token, returning the claims map.
   Does NOT validate the signature."
  [token]
  (let [parts (str/split token #"\.")]
    (when (= 3 (count parts))
      (json/read-str (base64-decode (second parts)) :key-fn keyword))))

(defn validate-token
  "Validate a JWT token. Returns {:valid true :claims map} or {:valid false :error msg}."
  [token]
  (let [parts (str/split token #"\.")]
    (if (not= 3 (count parts))
      {:valid false :error "invalid token"}
      (let [header (first parts)
            payload (second parts)
            given-sig (nth parts 2)
            expected-sig (hmac-sha256 (str header "." payload) secret-key)
            claims (try (json/read-str (base64-decode payload) :key-fn keyword)
                        (catch Exception _ nil))]
        (cond
          (nil? claims) {:valid false :error "invalid token"}
          (not= given-sig expected-sig) {:valid false :error "invalid token"}
          (< (:exp claims) (quot (System/currentTimeMillis) 1000))
          {:valid false :error "token expired"}
          :else {:valid true :claims claims})))))

(defn refresh-token
  "Refresh a valid token, extending its expiry."
  [token]
  (let [{:keys [valid claims]} (validate-token token)]
    (if valid
      {:ok (generate-token claims)}
      {:error "cannot refresh invalid token"})))
