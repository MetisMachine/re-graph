(ns re-graph.internals
  (:require [re-frame.core :as re-frame]
            [cljs-http.client :as http]
            [cljs.core.async :as a])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn- valid-graphql-errors?
  "Validates that response has a valid GraphQL errors map"
  [response]
  (and (map? response)
       (vector? (:errors response))
       (seq (:errors response))
       (every? map? (:errors response))))

(defn- insert-http-status
  "Inserts the HTTP status into the response for 3 conditions:
   1. Response contains a valid GraphQL errors map: update the map with HTTP status
   2. Response is a map but does not contain a valid errors map: merge in default errors
   3. Response is anything else: return default errors map"
  [response status]
  (let [f (fn [errors] (mapv #(assoc-in % [:extensions :status] status) errors))
        default-errors {:errors [{:message "The HTTP call failed."
                                  :extensions {:status status}}]}]
    (cond
      (valid-graphql-errors? response) (update response :errors f)
      (map? response) (merge response default-errors)
      :else default-errors)))

(re-frame/reg-fx
 ::send-http
 (fn [[http-url {:keys [request payload]} callback-fn]]
   (go (let [response (a/<! (http/post http-url (assoc request :json-params payload)))
             {:keys [status error-code]} response]
         (if (= :no-error error-code)
           (callback-fn (:body response))
           (callback-fn (insert-http-status (:body response) status)))))))

(re-frame/reg-fx
 ::send-ws
 (fn [[websocket payload]]
   (.send websocket (js/JSON.stringify (clj->js payload)))))

(re-frame/reg-fx
 ::call-callback
 (fn [[callback-fn payload]]
   (callback-fn payload)))

(re-frame/reg-event-fx
 ::callback
 (fn [_ [_ callback-fn payload]]
   {::call-callback [callback-fn payload]}))

(re-frame/reg-event-fx
 ::on-ws-data
 (fn [{:keys [db]} [_ subscription-id payload]]
   (if-let [callback-event (get-in db [:re-graph :subscriptions (name subscription-id) :callback])]
     {:dispatch (conj callback-event payload)}
     (js/console.debug "No callback-event found for subscription" subscription-id))))

(re-frame/reg-event-db
 ::on-ws-complete
 (fn [db [_ subscription-id]]
   (update-in db [:re-graph :subscriptions] dissoc (name subscription-id))))

(re-frame/reg-event-fx
  ::connection-init
  (fn [{:keys [db]} _]
    (let [ws (get-in db [:re-graph :websocket :connection])
          payload (get-in db [:re-graph :websocket :connection-init-payload])]
      (when payload
        {::send-ws [ws {:type "connection_init"
                        :payload payload}]}))))

(re-frame/reg-event-fx
 ::on-ws-open
 (fn [{:keys [db]} [_ ws]]
   (merge
    {:db (update-in db [:re-graph :websocket]
                    assoc
                    :connection ws
                    :ready? true
                    :queue [])}

    (let [resume? (get-in db [:re-graph :websocket :resume-subscriptions?])
          subscriptions (when resume? (->> db :re-graph :subscriptions vals (map :event)))
          queue (get-in db [:re-graph :websocket :queue])
          to-send (concat [[::connection-init]] subscriptions queue)]
      {:dispatch-n to-send}))))

(defn- deactivate-subscriptions [subscriptions]
  (reduce-kv (fn [subs sub-id sub]
               (assoc subs sub-id (assoc sub :active? false)))
             {}
             subscriptions))

(re-frame/reg-event-fx
 ::on-ws-close
 (fn [{:keys [db]}]
   (merge
    {:db (-> db
             (assoc-in [:re-graph :websocket :ready?] false)
             (update-in [:re-graph :subscriptions] deactivate-subscriptions))}
    (when-let [reconnect-timeout (get-in db [:re-graph :websocket :reconnect-timeout])]
      {:dispatch-later [{:ms reconnect-timeout
                         :dispatch [::reconnect-ws]}]}))))

(defn- on-ws-message [m]
  (let [data (js/JSON.parse (aget m "data"))]
    (condp = (aget data "type")
      "data"
      (re-frame/dispatch [::on-ws-data (aget data "id") (js->clj (aget data "payload") :keywordize-keys true)])

      "complete"
      (re-frame/dispatch [::on-ws-complete (aget data "id")])

      "error"
      (js/console.warn (str "GraphQL error for " (aget data "id") ": " (aget data "payload" "message")))

      (js/console.debug "Ignoring graphql-ws event" (aget data "type")))))

(defn- on-open [ws]
  (fn [e]
    (re-frame/dispatch [::on-ws-open ws])))

(defn- on-close [e]
  (re-frame/dispatch [::on-ws-close]))

(defn- on-error [e]
  (js/console.warn "GraphQL websocket error" e))

(re-frame/reg-event-fx
 ::reconnect-ws
 (fn [{:keys [db]}]
   (when-not (get-in db [:re-graph :websocket :ready?])
     {::connect-ws [(get-in db [:re-graph :websocket :url])]})))

(re-frame/reg-fx
 ::connect-ws
 (fn [[ws-url]]
   (let [ws (js/WebSocket. ws-url "graphql-ws")]
     (aset ws "onmessage" on-ws-message)
     (aset ws "onopen" (on-open ws))
     (aset ws "onclose" on-close)
     (aset ws "onerror" on-error))))

(re-frame/reg-fx
 ::disconnect-ws
 (fn [[ws]]
   (.close ws)))

(defn default-ws-url []
  (when (exists? (.-location js/window))
    (let [host-and-port (.-host js/window.location)
          ssl? (re-find #"^https" (.-origin js/window.location))]
      (str (if ssl? "wss" "ws") "://" host-and-port "/graphql-ws"))))

(defn generate-query-id []
  (.substr (.toString (js/Math.random) 36) 2 8))
