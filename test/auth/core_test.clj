(ns auth.core-test
  (:require [auth.core :as auth]
            [clojure.test :refer [deftest is testing]]))

(deftest refresh-token-preserves-subject-and-role
  (testing "refresh-token keeps the original claims needed to identify the user"
    (let [token (auth/generate-token {:id "user-123"
                                      :email "user@example.com"
                                      :role :admin})
          refreshed-token (:ok (auth/refresh-token token))
          refreshed-claims (:claims (auth/validate-token refreshed-token))]
      (is refreshed-token)
      (is (= "user-123" (:sub refreshed-claims)))
      (is (= "user@example.com" (:email refreshed-claims)))
      (is (= "admin" (:role refreshed-claims))))))
