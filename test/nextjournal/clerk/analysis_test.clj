(ns nextjournal.clerk.analysis-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer :all]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test :refer [match?]]
            [nextjournal.beholder]
            [nextjournal.clerk :as clerk :refer [defcached]]
            [nextjournal.clerk.analysis :as a]
            [weavejester.dependency :as dep]))

(deftest read-string-tests
  (testing "read-string should read regex's such that value equalility is preserved"
    (is (= '(fn [x] (clojure.string/split x (clojure.core/re-pattern "/")))
           (a/read-string "(fn [x] (clojure.string/split x #\"/\"))")))))

(defmacro with-ns-binding [ns-sym & body]
  `(binding [*ns* (find-ns ~ns-sym)]
     ~@body))

(deftest ns->path
  (testing "converts dashes to underscores"
    (is (= (str "rewrite_clj" fs/file-separator "parser")
           (a/ns->path (find-ns 'rewrite-clj.parser))))))

(def notebook "^:nextjournal.clerk/no-cache (ns example-notebook)

;; # 📶 Sorting

;; ## Sorting Sets
;; The following set should be sorted upon description

#{3 1 2}

;; ## Sorting Maps

{2 \"bar\" 1 \"foo\"}
")

(deftest parse-clojure-string
  (testing "is returning blocks with types and markdown structure attached"
    (is (match? (m/equals {:blocks [{:type :code, :text "^:nextjournal.clerk/no-cache (ns example-notebook)", :ns? true}
                                    {:type :markdown, :text " # 📶 Sorting\n"}
                                    {:type :markdown, :text " ## Sorting Sets\n The following set should be sorted upon description\n"}
                                    {:type :code, :text "#{3 1 2}"}
                                    {:type :markdown, :text " ## Sorting Maps\n"}
                                    {:type :code, :text "{2 \"bar\" 1 \"foo\"}"}],
                           :visibility #{:show},
                           :title "📶 Sorting",
                           :toc {:type :toc,
                                 :children [{:type :toc,
                                             :content [{:type :text, :text "📶 Sorting"}],
                                             :heading-level 1,
                                             :children [{:type :toc, :content [{:type :text, :text "Sorting Sets"}], :heading-level 2}
                                                        {:type :toc, :content [{:type :text, :text "Sorting Maps"}], :heading-level 2}]}]}})
                (a/parse-clojure-string {:doc? true} notebook)))))

(deftest no-cache?
  (testing "are variables set to no-cache?"
    (is (not (a/no-cache? (a/analyze+emit '(rand-int 10)))))
    (is (not (a/no-cache? (a/analyze+emit '(def random-thing (rand-int 1000))))))
    (is (not (a/no-cache? (a/analyze+emit '(defn random-thing [] (rand-int 1000))))))
    (is (a/no-cache? (a/analyze+emit '(def ^:nextjournal.clerk/no-cache random-thing (rand-int 1000)))))
    (is (a/no-cache? (a/analyze+emit '(defn ^:nextjournal.clerk/no-cache random-thing [] (rand-int 1000)))))
    (is (a/no-cache? (a/analyze+emit '(defn ^{:nextjournal.clerk/no-cache true} random-thing [] (rand-int 1000))))))

  (testing "is evaluating namespace set to no-cache?"
    (is (not (a/no-cache? '(rand-int 10))))

    (with-ns-binding 'nextjournal.clerk.analysis
      (is (nextjournal.clerk.analysis/no-cache? '(rand-int 10))))))

(deftest var-dependencies
  (is (match? #{'clojure.string/includes?
                'rewrite-clj.parser/parse-string-all}
              (a/var-dependencies '(defn foo
                                     ([] (foo "s"))
                                     ([s] (clojure.string/includes? (rewrite-clj.parser/parse-string-all s) "hi"))))))

  (testing "finds deps inside maps and sets"
    (is (match? '#{nextjournal.clerk.analysis-test/foo
                   nextjournal.clerk.analysis-test/bar}
                (with-ns-binding 'nextjournal.clerk.analysis-test
                  (intern *ns* 'foo :foo)
                  (intern *ns* 'bar :bar)
                  (:deps (a/analyze '{:k-1 foo :k-2 #{bar}})))))))


(deftest analyze
  (testing "quoted forms aren't confused with variable dependencies"
    (is (match? {:deps #{`inc}}
                (a/analyze '(do inc))))
    (is (empty? (:deps (a/analyze '(do 'inc))))))

  (testing "locals that shadow existing vars shouldn't show up in the deps"
    (is (empty? (:deps (a/analyze '(let [+ 2] +))))))

  (testing "symbol referring to a java class"
    (is (match? {:deps       #{'io.methvin.watcher.PathUtils}}
                (a/analyze 'io.methvin.watcher.PathUtils))))

  (testing "namespaced symbol referring to a java thing"
    (is (match? {:deps       #{'io.methvin.watcher.hashing.FileHasher/DEFAULT_FILE_HASHER}}
                (a/analyze 'io.methvin.watcher.hashing.FileHasher/DEFAULT_FILE_HASHER))))

  (is (match? {:ns-effect? false
               :var 'nextjournal.clerk.analysis/foo
               :deps       #{'rewrite-clj.parser/parse-string-all
                             'clojure.string/includes?}}
              (with-ns-binding 'nextjournal.clerk.analysis
                (a/analyze '(defn foo [s]
                              (clojure.string/includes? (rewrite-clj.parser/parse-string-all s) "hi"))))))

  (is (match? {:ns-effect?   false
               :var 'nextjournal.clerk.analysis/segments
               :deps         #{'clojure.string/split
                               'clojure.string/join}}
              (with-ns-binding 'nextjournal.clerk.analysis
                (a/analyze '(defn segments [s] (let [segments (clojure.string/split s)]
                                                 (clojure.string/join segments)))))))

  (is (match? {:form       '(in-ns 'user)
               :ns-effect? true
               :deps       #{'clojure.core/in-ns}}
              (a/analyze '(in-ns 'user))))

  (is (match? {:ns-effect? true
               :deps       (m/embeds #{'clojure.core/in-ns})}
              (a/analyze '(do (ns foo)))))

  (is (match? {:ns-effect? false
               :deps       #{'clojure.core/inc}}
              (a/analyze '(def my-inc inc))))

  (is (match? {:ns-effect? false
               :var 'nextjournal.clerk.analysis-test/!state
               :deps       #{'clojure.core/atom}}
              (with-ns-binding 'nextjournal.clerk.analysis-test
                (a/analyze '(defonce !state (atom {}))))))

  (is (match? {:ns-effect? false ;; TODO: change assertion to account for non-deterministic order
               :var  'nextjournal.clerk.analysis-test/foo-2
               :deps #{'nextjournal.clerk.analysis-test/foo}}
              (with-ns-binding 'nextjournal.clerk.analysis-test
                (a/analyze '(do (def foo :bar) (def foo-2 :bar))))))

  (testing "defcached should be treated like a normal def"
    (with-ns-binding 'nextjournal.clerk.analysis-test
      (is (= (dissoc (a/analyze '(def answer (do (Thread/sleep 4200) (inc 41)))) :form)
             (dissoc (a/analyze '(defcached answer (do (Thread/sleep 4200) (inc 41)))) :form)
             (dissoc (a/analyze '(clerk/defcached answer (do (Thread/sleep 4200) (inc 41)))) :form)
             (dissoc (a/analyze '(nextjournal.clerk/defcached answer (do (Thread/sleep 4200) (inc 41)))) :form))))))

(deftest symbol->jar
  (is (a/symbol->jar 'io.methvin.watcher.PathUtils))
  (is (a/symbol->jar 'io.methvin.watcher.PathUtils/cast))
  (testing "does not resolve jdk builtins"
    (is (not (a/symbol->jar 'java.net.http.HttpClient/newHttpClient)))))

(defn analyze-string [s]
  (-> (a/parse-clojure-string {:doc? true} s)
      a/analyze-doc))

(deftest analyze-doc
  (is (match? (m/equals
               {:graph {:dependencies {'(ns example-notebook) set?}
                        :dependents   map?}
                :blocks [{:type :code
                          :text "^:nextjournal.clerk/no-cache (ns example-notebook)"
                          :form '(ns example-notebook)
                          :ns?  true}
                         {:type :code
                          :text "#{3 1 2}"
                          :form #{1 2 3}}]
                :toc {:type :toc}
                :visibility #{:show}
                :->analysis-info {'(ns example-notebook) {:form '(ns example-notebook),
                                                          :deps set?}
                                  #{1 3 2} {:form '#{1 3 2}}}})
              (analyze-string "^:nextjournal.clerk/no-cache (ns example-notebook)
#{3 1 2}"))))


(deftest circular-dependency
  (is (match? {:graph {:dependencies {'(ns circular) any?
                                      'circular/b #{clojure.core/str 'circular/a+circular/b}
                                      'circular/a #{clojure.core/str 'circular/a+circular/b}}}
               :->analysis-info {'circular/a any?
                                 'circular/b any?
                                 'circular/a+circular/b {:form '(do (def a (str "boom " b)) (def b(str a " boom")))}}}
              (analyze-string "(ns circular)
(declare a)
(def b (str a \" boom\"))
(def a (str \"boom \" b))"))))
