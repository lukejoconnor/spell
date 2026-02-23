(ns spell.grammar-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [spell.grammar :as grammar]))

(deftest analyze-prefix-test
  (testing "tracks open delimiters and mode"
    (let [state (grammar/analyze-prefix "(do '[1 2 ")]
      (is (= :normal (:mode state)))
      (is (= 0 (:pending-prefix state)))
      (is (= [{:type :root} {:type :list} {:type :vec}] (:frames state)))))

  (testing "tracks dangling unary reader prefixes"
    (let [state (grammar/analyze-prefix "(do '")]
      (is (= 1 (:pending-prefix state)))
      (is (= [{:type :root} {:type :list}] (:frames state)))))

  (testing "tracks string and escape modes"
    (is (= :string (:mode (grammar/analyze-prefix "(str \"abc"))))
    (is (= :string-escape (:mode (grammar/analyze-prefix "(str \"abc\\")))))

  (testing "tracks trailing comment mode"
    (is (= :comment (:mode (grammar/analyze-prefix "(do ; note")))))

  (testing "throws on mismatched delimiter"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Mismatched closing delimiter"
          (grammar/analyze-prefix "(]")))))

(deftest suffix-lark-grammar-test
  (testing "dangling quote requires one expression before continuation"
    (let [g (grammar/suffix-lark-grammar "(do '")]
      (is (str/starts-with? g "start: expr "))
      (is (str/includes? g "\")\""))))

  (testing "odd map state requires value before close"
    (let [g (grammar/suffix-lark-grammar "{:a ")]
      (is (str/includes? g "c1: expr (expr expr)* \"}\" c0"))))

  (testing "string mode starts with string-tail token"
    (let [g (grammar/suffix-lark-grammar "(def x \"abc")]
      (is (str/starts-with? g "start: STR_TAIL "))
      (is (str/includes? g "STR_TAIL: /([^\"\\\\]|\\\\.)*\"/"))))

  (testing "string escape mode consumes escaped char then closes string"
    (let [g (grammar/suffix-lark-grammar "(def x \"abc\\")]
      (is (str/starts-with? g "start: STR_ESC_CHAR STR_TAIL "))
      (is (str/includes? g "STR_ESC_CHAR: /./"))))

  (testing "comment mode allows comment tail and newline continuation"
    (let [g (grammar/suffix-lark-grammar "(do ; note")]
      (is (str/starts-with? g "start: COMMENT_REST NL "))
      (is (str/includes? g "COMMENT_REST: /[^\\n]*/"))))

  (testing "full completion-style prefix is usually under 2k"
    (let [prefix "(quine completion (eval (do (quine prompt \"hello\") "
          g (grammar/suffix-lark-grammar prefix)]
      (is (< (count g) 2000)))))

(deftest openai-format-test
  (testing "returns OpenAI grammar-format map"
    (let [fmt (grammar/openai-suffix-grammar-format "(do ")]
      (is (= "grammar" (:type fmt)))
      (is (= "lark" (:syntax fmt)))
      (is (string? (:definition fmt)))
      (is (str/starts-with? (:definition fmt) "start:")))))
