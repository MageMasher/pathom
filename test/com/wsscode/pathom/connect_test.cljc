(ns com.wsscode.pathom.connect-test
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [com.wsscode.pathom.connect :as p.connect]
            [com.wsscode.pathom.core :as p]))

(def users
  {1 {:user/id 1 :user/name "Mel" :user/age 26 :user/login "meel"}})

(def users-login
  {"meel" (get users 1)})

(def user-addresses
  {1 "Live here somewhere"})

(defn user-by-id [_ {:keys [user/id] :as input}]
  (or (get users id) (throw (ex-info "user not found" {:input input}))))

(s/fdef user-by-id
  :args (s/cat :env ::p/env :user (s/keys :req [:user/id]))
  :ret (s/keys :req [:user/id :user/name :user/age :user/login]))

(defn user-by-login [_ {:keys [user/login]}]
  (or (get users-login login) (throw (ex-info "user not found" {}))))

(s/fdef user-by-login
  :args (s/cat :env ::p/env :user (s/keys :req [:user/login]))
  :ret (s/keys :req [:user/id :user/name :user/age :user/login]))

(defn user-address [_ {:keys [user/id]}]
  {:user/address (get user-addresses id)})

(s/fdef user-address
  :args (s/cat :env ::p/env :user (s/keys :req [:user/id]))
  :ret (s/keys :req [:user/address] :gen #(s/gen #{{:user/address "bla"}})))

(defn user-login-from-email [_ {:user/keys [email]}]
  (if (= email "a@b.c")
    {:user/login "meel"}))

(s/fdef user-login-from-email
  :args (s/cat :env ::env :user (s/keys :req [:user/email]))
  :ret (s/keys :req [:user/login]))

(defn user-network [_ {:user/keys [id]}]
  (if (= 1 id)
    {:user/network {:network/id "twitter" :network/name "mell"}}))

(s/fdef user-network
  :args (s/cat :env ::env :user (s/keys :req [:user/id]))
  :ret (s/keys :req [:user/network]))

(def indexes
  (-> {}
      (p.connect/add `user-by-id)
      (p.connect/add `user-by-login)
      (p.connect/add `user-login-from-email)
      (p.connect/add `user-address)
      (p.connect/add `user-network
        {:input  [:user/id]
         :output [{:user/network [:network/id :network/name]}]})))

(def parser
  (p/parser {::p/plugins
             [(p/env-plugin {::p/reader          [{:cache (comp deref ::p/request-cache)}
                                                  p/map-reader
                                                  p.connect/reader
                                                  p.connect/ident-reader]
                             ::p.connect/indexes indexes})
              p/request-cache-plugin]}))

(deftest test-resolver->in-out
  (is (= (p.connect/resolver->in-out `user-by-id)
         {:input [:user/id] :output [:user/name :user/id :user/login :user/age]}))

  (is (= (p.connect/resolver->in-out `user-by-id)
         {:input [:user/id] :output [:user/name :user/id :user/login :user/age]})))

(deftest test-merge-io
  (is (= (p.connect/merge-io {:user/name :user/name}
                             {:user/name :user/name})
         {:user/name :user/name}))
  (is (= (p.connect/merge-io {:user/name :user/name}
                             {:user/email :user/email})
         {:user/name  :user/name
          :user/email :user/email}))
  (is (= (p.connect/merge-io {:user/address :user/address}
                             {:user/address {:address/name :address/name}})
         {:user/address {:address/name :address/name}}))
  (is (= (p.connect/merge-io {:user/address {:address/street :address/street}}
                             {:user/address {:address/name :address/name}})
         {:user/address {:address/name   :address/name
                         :address/street :address/street}})))

(deftest test-add
  (is (= (p.connect/add {} `user-by-login)
         {:idents    #{:user/login}
          :index-fio {`user-by-login {:input  [:user/login]
                                      :output [:user/name
                                               :user/id
                                               :user/login
                                               :user/age]}}
          :index-io  {#{:user/login} {:user/age   :user/age
                                      :user/id    :user/id
                                      :user/login :user/login
                                      :user/name  :user/name}}
          :index-oif #:user{:age  {[:user/login] `user-by-login}
                            :id   {[:user/login] `user-by-login}
                            :name {[:user/login] `user-by-login}}}))

  (is (= (-> {}
             (p.connect/add `user-by-id)
             (p.connect/add `user-network
               {:output [{:user/network [:network/id :network/name]}]}))
         `{:idents    #{:user/id}
           :index-fio {user-by-id   {:input  [:user/id]
                                     :output [:user/name
                                              :user/id
                                              :user/login
                                              :user/age]}
                       user-network {:input  [:user/id]
                                     :output [#:user{:network [:network/id
                                                               :network/name]}]}}
           :index-io  {#{:user/id} #:user{:age     :user/age
                                          :id      :user/id
                                          :login   :user/login
                                          :name    :user/name
                                          :network {:network/id   :network/id
                                                    :network/name :network/name}}}
           :index-oif #:user{:age     {[:user/id] user-by-id}
                             :login   {[:user/id] user-by-id}
                             :name    {[:user/id] user-by-id}
                             :network {[:user/id] user-network}}})))

(deftest test-reader
  (testing "follows a basic attribute"
    (is (= (parser {::p/entity (atom {:user/id 1})}
             [:user/name])
           {:user/name "Mel"})))

  (testing "follows a basic attribute"
    (is (= (parser {::p/entity (atom {:user/id 1 :user/foo "bar"})}
             [:user/name :cache])
           {:user/name "Mel"
            :cache     {[`user-by-id {:user/id 1}] {:user/age   26
                                                    :user/id    1
                                                    :user/login "meel"
                                                    :user/name  "Mel"}}})))

  (testing "not found when there is no attribute"
    (is (= (parser {::p/entity (atom {:user/id 1})}
             [:user/not-here])
           {:user/not-here ::p/not-found})))

  (testing "not found if requirements aren't met"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requirements could not be met."
          (= (parser {::p/entity (atom {})}
               [:user/name])))))

  (testing "error when an error happens"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"user not found"
          (= (parser {::p/entity (atom {:user/id 2})}
               [:user/name])))))

  (testing "read dependend attributes when neeeded"
    (is (= (parser {::p/entity (atom {:user/login "meel"})}
             [:user/address])
           {:user/address "Live here somewhere"})))

  (testing "deeper level deps"
    (is (= (parser {::p/entity (atom {:user/email "a@b.c"})}
             [:user/address])
           {:user/address "Live here somewhere"})))

  (testing "nested resource"
    (is (= (parser {::p/entity (atom {:user/login "meel"})}
             [{:user/network [:network/id]}])
           {:user/network {:network/id "twitter"}})))

  (testing "ident read"
    (is (= (parser {} [{[:user/id 1] [:user/name]}])
           {[:user/id 1] {:user/name "Mel"}}))))

(def index
  {:index-io {#{:customer/id}                                         #:customer{:external-ids  :customer/external-ids
                                                                                 :cpf           :customer/cpf
                                                                                 :email         :customer/email
                                                                                 :boletos       #:boleto{:customer-id  :boleto/customer-id
                                                                                                         :beneficiary  #:beneficiary{:branch-number  :beneficiary/branch-number
                                                                                                                                     :account-number :beneficiary/account-number
                                                                                                                                     :document       :beneficiary/document
                                                                                                                                     :bank           :beneficiary/bank
                                                                                                                                     :id             :beneficiary/id}
                                                                                                         :id           :boleto/id
                                                                                                         :seu-numero   :boleto/seu-numero
                                                                                                         :nosso-numero :boleto/nosso-numero
                                                                                                         :bank         :boleto/bank}
                                                                                 :address-line1 :customer/address-line1
                                                                                 :id            :customer/id
                                                                                 :printed-name  :customer/printed-name}
              #{:customer/account-id}                                 #:customer{:beneficiary #:beneficiary{:id             :beneficiary/id
                                                                                                            :bank           :beneficiary/bank
                                                                                                            :branch-number  :beneficiary/branch-number
                                                                                                            :account-number :beneficiary/account-number
                                                                                                            :document       :beneficiary/document}}
              #{:boleto/seu-numero :boleto/nosso-numero :boleto/bank} #:boleto{:registration :boleto/registration}
              #{:boleto/customer-id}                                  #:boleto{:customer #:customer{:id :customer/id}}
              #{:customer/cpf}                                        #:customer{:cpf   :customer/cpf
                                                                                 :email :customer/email
                                                                                 :name  :customer/name
                                                                                 :id    :customer/id}}
   :idents   #{:customer/cpf :customer/account-id :customer/id :boleto/customer-id}})

(deftest test-discover
  (testing "not found"
    (is (= (p.connect/discover-attrs index [:noop])
           {})))

  (testing "expand from dependencies"
    (is (= (p.connect/discover-attrs index [:customer/cpf])
           #:customer{:cpf           :customer/cpf
                      :email         :customer/email
                      :name          :customer/name
                      :id            :customer/id
                      :external-ids  :customer/external-ids
                      :boletos       #:boleto{:customer-id  :boleto/customer-id
                                              :beneficiary  #:beneficiary{:branch-number  :beneficiary/branch-number
                                                                          :account-number :beneficiary/account-number
                                                                          :document       :beneficiary/document
                                                                          :bank           :beneficiary/bank
                                                                          :id             :beneficiary/id}
                                              :id           :boleto/id
                                              :seu-numero   :boleto/seu-numero
                                              :nosso-numero :boleto/nosso-numero
                                              :bank         :boleto/bank}
                      :address-line1 :customer/address-line1
                      :printed-name  :customer/printed-name})))

  (testing "children level lookup"
    (is (= (p.connect/discover-attrs index [:boleto/beneficiary :customer/boletos :customer/cpf])
           #:beneficiary{:branch-number  :beneficiary/branch-number
                         :account-number :beneficiary/account-number
                         :document       :beneficiary/document
                         :bank           :beneficiary/bank
                         :id             :beneficiary/id}))

    (is (= (p.connect/discover-attrs index [:boleto/beneficiary :customer/boletos :customer/cpf :ignore-me])
           #:beneficiary{:branch-number  :beneficiary/branch-number
                         :account-number :beneficiary/account-number
                         :document       :beneficiary/document
                         :bank           :beneficiary/bank
                         :id             :beneficiary/id})))

  (testing "attributes with multiple inputs"
    (is (= (p.connect/discover-attrs index [:customer/boletos :customer/cpf])
           #:boleto{:customer-id  :boleto/customer-id
                    :beneficiary  #:beneficiary{:branch-number  :beneficiary/branch-number
                                                :account-number :beneficiary/account-number
                                                :document       :beneficiary/document
                                                :bank           :beneficiary/bank
                                                :id             :beneficiary/id}
                    :id           :boleto/id
                    :seu-numero   :boleto/seu-numero
                    :nosso-numero :boleto/nosso-numero
                    :bank         :boleto/bank
                    :registration :boleto/registration
                    :customer     #:customer{:id :customer/id}}))))
