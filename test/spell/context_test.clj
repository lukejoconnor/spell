(ns spell.context-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [spell.context :as context]
            [spell.eval :as eval]
            [spell.macros :as macros]
            [spell.parse :as parse]
            [spell.runtime :as runtime]
            [spell.user :as user]))

(use-fixtures :each (fn [f] (binding [context/*context* (context/new-context)] (f))))

(defn- recover [text]
  (let [r (eval/spell-eval (parse/read-first text) {})]
    (is (eval/ok? r) (pr-str (dissoc r :ok)))
    (:ok r)))

(defn- rendered [v cap] (context/serialize-value v cap))

(deftest configuration-and-removal
  (is (= {:max-chars 10000} (context/new-context)))
  (is (not (contains? eval/core-builtins 'stored)))
  (is (not (contains? eval/core-builtins 'deep-truncate)))
  (doseq [bad [-1 0 127 1.2 "1000"]]
    (is (thrown? clojure.lang.ExceptionInfo (context/snapshot "x" bad))))
  (binding [context/*context* (context/new-context {:max-chars 128})]
    (is (= (str "\"" (apply str (repeat 1500 "x")) "\"")
           (rendered (apply str (repeat 1500 "x")) 2000)))))

(deftest strings-grace-head-tail-and-escapes
  (doseq [cap [128 300 10000]
          text [(str "HEAD" (apply str (repeat 20000 "x")) "TAIL")
                (str "HEAD" (apply str (repeat 20000 "\"\\\n\t😀")) "TAIL")]]
    (let [s (rendered text cap) v (recover s)]
      (is (<= (count s) (* cap 1.2)) [cap (count s)])
      (is (string? v))
      (is (str/starts-with? v "HEAD"))
      (is (str/ends-with? v "TAIL"))
      (is (str/includes? v "omitted"))
      (is (= s (context/render-form (context/value-form v))))))
  (is (= (apply str (repeat 118 "x")) (recover (rendered (apply str (repeat 118 "x")) 128))))
  (is (= 153 (count (rendered (apply str (repeat 151 "x")) 128))))
  (is (< (count (rendered (apply str (repeat 152 "x")) 128)) 154)))

(deftest no-broken-surrogate-cuts
  (doseq [cap [128 129 200 300]
          offset (range 4)]
    (let [text (str (apply str (repeat offset "x")) (apply str (repeat 1000 "😀")))
          value (recover (rendered text cap))]
      (doseq [i (range (count value))]
        (let [c (.charAt ^String value i)]
          (when (Character/isHighSurrogate c)
            (is (and (< (inc i) (count value))
                     (Character/isLowSurrogate (.charAt ^String value (inc i))))))
          (when (Character/isLowSurrogate c)
            (is (and (pos? i) (Character/isHighSurrogate (.charAt ^String value (dec i)))))))))))

(deftest independent-output-budgets
  (let [a (apply str (repeat 1000 "a")) b (apply str (repeat 1000 "b"))
        text (context/serialize-contribution [{:name 'a :value a} {:name 'b :value b}
                                              {:form '(prune 3)}] 128)
        forms (parse/read-all text)]
    (is (= 3 (count forms)))
    (is (> (count text) 153))
    (doseq [form (take 2 forms)] (is (<= (count (context/render-form (nth form 2))) 153)))
    (is (= '(prune 3) (last forms)))))

(deftest numbered-nesting-gaps-and-subvec
  (let [lines (with-meta (mapv #(str "line-" %) (range 500)) {:spell/first-line 41})
        text (rendered {:out lines} 500)
        v (:out (recover text))
        positions (mapv #(context/line-position v %) (range (count v)))
        gap (.indexOf positions nil)]
    (is (vector? v))
    (is (every? string? v))
    (is (str/includes? text "first-line"))
    (is (some nil? positions))
    (is (= 41 (first positions)))
    (is (= 540 (last positions)))
    (is (<= 0 gap))
    (is (str/includes? (nth v gap) "omitted"))
    (let [slice (context/subvec-lines v gap (count v))]
      (is (nil? (context/line-position slice 0)))
      (is (= 540 (context/line-position slice (dec (count slice))))))
    (is (= text (context/render-form (context/value-form (recover text)))))))

(deftest huge-lines-are-strings-and-bounded
  (let [lines (with-meta [(str "HEAD" (apply str (repeat 10000 "a")) "TAIL")]
                {:spell/first-line 77})
        text (rendered {:out lines} 500)
        rows (:out (recover text))]
    (is (<= (count text) 600))
    (is (every? string? rows))
    (is (str/includes? (first rows) "omitted"))
    (is (= 77 (context/line-position rows 0)))))

(deftest container-kinds-and-map-key-collisions
  (doseq [[value pred] [[(vec (range 10000)) vector?]
                        [(apply list (range 10000)) list?]
                        [(set (range 10000)) set?]
                        [(into {} (map #(vector % (str %)) (range 10000))) map?]]]
    (let [text (rendered value 300)]
      (is (<= (count text) 360))
      (is (pred (recover text)))
      (is (str/includes? text "omitted"))))
  (let [m (array-map :spell/omitted "USER" :big (apply str (repeat 10000 "x")))
        v (recover (rendered m 128))]
    (is (= "USER" (:spell/omitted v))))
  (let [k1 (str (apply str (repeat 1000 "k")) "A")
        k2 (str (apply str (repeat 1000 "k")) "B")
        v (recover (rendered {k1 1 k2 2} 128))]
    (is (not-any? #(and (string? %) (not (#{k1 k2} %))) (keys v)))))

(deftest lazy-opaque-and-errors-are-total
  (let [seen (atom 0)
        value (map (fn [n] (swap! seen inc) n) (range))
        text (rendered value 128)]
    (is (< @seen 1000))
    (is (list? (recover text)))
    (is (str/includes? text "omitted")))
  (doseq [v [(Object.) (atom 1) (fn [] 1)
             (lazy-seq (throw (ex-info "bad sequence" {})))
             (reduce (fn [v _] [v]) nil (range 1000))]]
    (let [text (rendered v 128)]
      (is (<= (count text) 153))
      (is (some? (recover text))))))

(deftest external-envelopes-preserve-status
  (doseq [out [(str (apply str (repeat 10000 "x")) "TAIL")
               (vec (range 100000))
               (lazy-seq (throw (ex-info "bad" {})))]]
    (let [original {:ok false :exit 17 :status 503 :out out :err nil :truncated false}
          v (recover (rendered original 300))]
      (is (= false (:ok v)))
      (is (= 17 (:exit v)))
      (is (= 503 (:status v)))
      (is (true? (:truncated v)))
      (is (contains? v :out))
      (is (nil? (:err v))))))

(deftest macros-compute-full-before-snapshot
  (let [original (apply str (repeat 10000 "a"))
        env {'completion '(quine completion (eval (do)))
             'raw original '!llm-self (fn [q _] q)}
        result (eval/spell-eval
                 (macros/spell-macroexpand-1 '(!call-now {:max-chars 128} n (count raw) out raw)) env)
        q (:ok result)
        forms (rest (second (last q)))
        evaluated (eval/spell-eval (list* 'do forms) {})]
    (is (eval/ok? result) (pr-str result))
    (is (= 10000 (get-in evaluated [:env 'n])))
    (is (< (count (get-in evaluated [:env 'out])) 200))
    (is (= 10000 (count original)))))

(deftest prefix-rendering-never-recaps
  (let [text (rendered {:nested (with-meta ["a" "b"] {:spell/first-line 88})
                       :big (apply str (repeat 1000 "z"))} 2000)
        form (parse/read-first text)
        q (list 'quine 'completion (list 'eval (list 'do (list 'def 'x form))))
        prefix (binding [context/*context* (context/new-context {:max-chars 128})]
                 (eval/serialize-quine-prefix q))]
    (is (str/includes? prefix text))
    (is (= prefix (eval/serialize-quine-prefix (parse/read-first (parse/balance-parens prefix)))))))

(deftest message-body-only-snapshot
  (let [body (apply str (repeat 10000 "x"))
        envelope {:from :sender :edge-id 99 :expects-response true :id 22 :body body}
        form (first (context/contribution-forms [{:name 'msg :value envelope :body-only? true}] 128))
        r (eval/spell-eval form {})
        v (:ok r)]
    (is (= (dissoc envelope :body) (dissoc v :body)))
    (is (str/includes? (:body v) "omitted"))
    (is (str/includes? (context/render-form form) ":edge-id 99"))))

(deftest spell-function-source-budget-is-not-traversal-fuel
  (let [value {:spell/fn true :params ['x] :body '((+ x 1))}
        changed (volatile! false)
        normalized (#'context/normalize-value value (volatile! 1) 0 changed)]
    (is (= value normalized))
    (is (false? @changed))
    (is (= value (recover (rendered value 128))))))

(deftest output-macros-reject-legacy-trailing-limits
  (doseq [form ['(!call-now result value 128)
               '(!peek result value 128)
               '(!peek-now result value 128)]]
    (is (thrown? clojure.lang.ExceptionInfo
          (macros/spell-macroexpand-1 form)))))

(deftest numbered-gap-coordinates-survive-tiny-row-budgets
  (let [rows (with-meta (mapv #(str "row-" %) (range 1000)) {:spell/first-line 100})
        result (#'context/short-vector rows 128)
        gaps (keep-indexed (fn [i row]
                             (when (nil? (context/line-position result i)) row)) result)]
    (is (seq gaps))
    (is (every? string? gaps))
    (doseq [gap gaps]
      (is (re-find #"omitted [0-9]+ rows; source [0-9]+\.\.[0-9]+" gap))
      (is (not (str/includes? gap "UTF-16"))))))

(deftest inert-user-message-decoder
  (doseq [body [{(symbol "a b") [(symbol "nil") (keyword "a b")]}
               (array-map '(symbol "a b") :literal-key
                 :rows (with-meta ["a" "b"] {:spell/first-line 17}))
               '(globals/get :secret)
               {:nested '(do (throw "must not run") :bad)}]]
    (let [envelope {:from :sender :edge-id 8 :body body}
          raw (str "(quine completion (eval (do (def msg-1 "
                (rendered envelope 10000) "))))")
          messages (#'user/extract-messages raw)]
      (is (= [{:name 'msg-1 :msg envelope}] messages) raw)))
  (doseq [form ['(globals/get :secret) '(do (throw "must not run") :bad)]]
    (is (= form (#'user/decode-message-value form)))))

(deftest persist-and-prune-keep-materialized-snapshot-exact
  (let [rows (with-meta ["first" (apply str (repeat 1000 "x")) "last"]
               {:spell/first-line 77})
        source (rendered rows 2000)
        snapshot (recover source)
        evaluated (eval/spell-eval '(persist kept (subvec rows 1)) {'rows snapshot})
        kept (get-in evaluated [:env 'kept])
        q (list 'quine 'completion
            (list 'eval (list 'do (list 'def 'rows (parse/read-first source))
                         '(prune) '(persist kept (subvec rows 1)))))
        edited (eval/apply-edits q (:env evaluated))
        prefix (binding [context/*context* (context/new-context {:max-chars 128})]
                 (eval/serialize-quine-prefix edited))
        retained-source (context/render-form (context/value-form kept))]
    (is (eval/ok? evaluated))
    (is (= 78 (context/line-position kept 0)))
    (is (= 1000 (count (first kept))))
    (is (not (str/includes? prefix "(def rows")))
    (is (str/includes? prefix retained-source))
    (is (= prefix (eval/serialize-quine-prefix
                    (parse/read-first (parse/balance-parens prefix)))))))

(deftest external-envelope-truncation-is-local
  (let [clean {:ok true :out "x" :err nil :truncated false}
        changed (volatile! true)
        normalized (#'context/normalize-value clean (volatile! 1000) 0 changed)]
    (is (= clean normalized)))
  (let [clean {:ok true :out "x" :err nil :truncated false}
        result (recover (rendered [(Object.) clean] 1000))]
    (is (= clean (second result)))))

(deftest unusual-atoms-and-generic-map-keys
  (doseq [value [(symbol "a b") (symbol "nil") (symbol "1")
                 (keyword "a b") (keyword "x/y z")
                 {(symbol "a b") [(symbol "nil") (keyword "a b")]}
                 {nil 1 true 2 17 3 [1 2] 4 '(a b) 5 #{:x} 6 {:x 1} 7}
                 #{(symbol "a b") (keyword "a b")}]]
    (let [text (rendered value 10000)]
      (is (= value (recover text)) text)
      (is (= text (context/render-form (context/value-form (recover text))))))))

(deftest unsupported-map-key-does-not-overwrite-user-marker
  (let [value (array-map (Object.) :unsupported :spell/omitted "USER" :good 7)
        result (recover (rendered value 1000))]
    (is (= "USER" (:spell/omitted result)))
    (is (= 7 (:good result)))
    (is (= 3 (count result)))))

(deftest numbered-rows-remain-strings-after-traversal-fuel
  (let [rows (with-meta (vec (repeat 500 "x")) {:spell/first-line 10})
        changed (volatile! false)
        normalized (#'context/normalize-value rows (volatile! 5) 0 changed)]
    (is (vector? normalized))
    (is (every? string? normalized))
    (is (= 10 (context/line-position normalized 0)))
    (is (= 509 (context/line-position normalized (dec (count normalized)))))))

(defn- envelope-roundtrip [value limit]
  (let [snapshot (context/snapshot value limit)
        text (context/render-form (:form snapshot))
        result (eval/spell-eval (parse/read-first text) {})]
    {:snapshot snapshot :text text :result result :value (:ok result)}))

(deftest envelope-extras-use-the-snapshot-budget
  (let [seen (atom 0)
        original {:ok false :exit 17 :status 503 :out "payload" :err nil :truncated false
                  :metadata (map (fn [n] (swap! seen inc) n) (range 10000))}
        {:keys [text result value]} (envelope-roundtrip original 512)]
    (is (< @seen 2000) (str "realized " @seen " elements"))
    (is (<= (count text) 614) (str "rendered " (count text) " chars"))
    (is (eval/ok? result))
    (is (= {:ok false :exit 17 :status 503} (select-keys value [:ok :exit :status])))
    (is (= "payload" (:out value)))
    (is (nil? (:err value)))
    (is (true? (:truncated value)))))

(deftest opaque-extra-cannot-replace-the-result-envelope
  (let [original {:ok false :exit 17 :status 503 :out "payload" :err "diagnostic"
                  :truncated false :metadata (Object.)}
        {:keys [result value]} (envelope-roundtrip original 10000)]
    (is (eval/ok? result))
    (is (= (select-keys original [:ok :exit :status :out :err])
           (select-keys value [:ok :exit :status :out :err])))
    (is (true? (:truncated value)))
    (is (true? (get-in value [:metadata :spell/representation-unavailable])))))

(deftest extra-keys-preserve-collision-and-unsupported-key-rules
  (let [original (array-map :ok true :out "ok" :err nil :truncated false
                           (Object.) :unsupported :spell/omitted "USER" :good 7)
        {:keys [result value]} (envelope-roundtrip original 10000)]
    (is (eval/ok? result))
    (is (= "USER" (:spell/omitted value)))
    (is (= 7 (:good value)))
    (is (= :unknown (:spell/omitted-1 value)))
    (is (true? (:truncated value)))))

(deftest nested-third-party-envelope-shape-is-bounded
  (let [seen (atom 0)
        third-party {:ok true :out "inner" :err nil :truncated false
                     :metadata (map (fn [n] (swap! seen inc) n) (range 10000))}
        outer {:ok true :out third-party :err nil :truncated false}
        {:keys [text result value]} (envelope-roundtrip outer 512)]
    (is (< @seen 2000))
    (is (<= (count text) 614))
    (is (eval/ok? result))
    (is (true? (:ok value)))
    (is (map? (:out value)))
    (is (true? (get-in value [:out :ok])))))

(deftest reserved-key-names-do-not-exempt-arbitrary-control-values
  (doseq [control [:exit :status :truncated]]
    (let [seen (atom 0)
          third-party (assoc {:ok true :out "inner" :err nil :truncated false}
                        control (map (fn [n] (swap! seen inc) n) (range 10000)))
          outer {:ok true :out third-party :err nil :truncated false}
          {:keys [text result value]} (envelope-roundtrip outer 512)]
      (is (< @seen 2000) (str control " realized " @seen))
      (is (<= (count text) 614))
      (is (eval/ok? result))
      (is (true? (:ok value)))
      (is (map? (:out value)))))
  (let [third-party {:ok true :out "inner" :err nil :truncated false :status (Object.)}
        outer {:ok true :out third-party :err nil :truncated false}
        {:keys [result value]} (envelope-roundtrip outer 10000)]
    (is (eval/ok? result))
    (is (true? (:ok value)))
    (is (true? (get-in value [:out :status :spell/representation-unavailable])))))
