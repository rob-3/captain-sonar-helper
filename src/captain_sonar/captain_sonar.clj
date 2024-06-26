(ns captain-sonar.captain-sonar
  (:gen-class)
  (:require
   [cheshire.core :as json]
   [clojure.walk :refer [keywordize-keys]]
   [hiccup.page :as page]
   [hiccup2.core :as h]
   [ring.adapter.jetty :as jetty]
   [ring.middleware.cookies :as middleware]
   [ring.util.codec :refer [form-decode]]
   [ring.websocket :as ws]
   [clojure.core.async :refer [<!! >!!] :as a]))

(defmacro html [& args]
  `(str (h/html ~@args)))

(defn file-rsp [f]
  {:status 200 :headers {"vary" "hx-request" "cache-control" "no-store"} :body (slurp f)})

(defn query-params [request]
  (-> request :query-string form-decode keywordize-keys))

(defn index [uuid]
  (let [url (str "/room?id=" uuid)]
    [:div.container
     [:h1.title "Captain Sonar"]
     [:button.button {:hx-get url
                      :hx-push-url "true"
                      :hx-swap "outerHTML"} "Create a room"]]))

(defn request-type [r]
  (let [headers (:headers r)
        htmx? (get headers "hx-request")]
    (if (= htmx? "true")
      :htmx
      :default)))

(defn page-skeleton [children]
  (page/html5 (page/include-css "/index.css")
              [:script {:type "text/javascript"
                        :src "https://unpkg.com/htmx.org@1.9.12"
                        :integrity "sha384-ujb1lZYygJmzgSwoxRggbCHcjc0rB2XoQrxeTUQyRjrOnlCoYta87iKBWq3EsdM2"
                        :crossorigin "anonymous"}]
              [:script {:src "https://unpkg.com/htmx.org@1.9.12/dist/ext/ws.js"}]
              (h/html [:div#ws.container {:hx-ext "ws" :ws-connect "/ws"}
                       children])))

(ns-unmap *ns* 'index-page)
(defmulti index-page request-type)
(defmethod index-page :default [_request]
  (let [uuid (random-uuid)]
    (page-skeleton (index uuid))))

;; FIXME we should probably maintain a user data map that allows user->room too
;; That way we can prevent people from joining multiple rooms by mistake.
(defonce state (atom {:rooms {} :users {}}))

(defn room-html [room-id player-id {:keys [admin players spectators]}]
  {:pre [room-id player-id (not (nil? admin)) players spectators]}
  (let [playing? (contains? players player-id)
        spectating? (contains? spectators player-id)
        in-room? (or playing? spectating?)
        admin? (= player-id admin)]
    [:div#app.container (when-not in-room? {:ws-send ""
                                            :hx-vals (json/generate-string {"event" "join-room-spec"
                                                                            "room" room-id})
                                            :hx-trigger "load delay:1ms"})
     (when admin? [:div "You are the admin. (" player-id ")"])
     (for [player players]
       (if (= player player-id)
         (when-not admin? [:div (str "You (" player ") are in the room.")])
         [:div (str "Player " player " is in the room.")]))
     (for [spec spectators]
       (if (= spec player-id)
         (when-not admin? [:div (str "You (" spec ") are watching.")])
         [:div (str spec " is watching.")]))
     (when in-room? [:button.button {:hx-push-url "true" :hx-post "/leave-room"} "Leave Room"])
     (when spectating? [:form {:ws-send "" :id "join-form"}
                        [:input {:type "hidden" :name "room" :value room-id}]
                        [:input {:type "text" :name "username" :required ""}]
                        [:button.button {:type "submit" :name "event" :value "join-room"} "Join room"]])]))

(defn add-to-room [state room player role]
  {:pre [state room player (contains? #{:players :spectators} role)]}
  (-> state
      (update-in [:rooms room] (fnil #(update % role conj player)
                                     (hash-map :admin player :players #{} :spectators #{} role #{player})))
      (assoc-in [:users player :room] room)))

(defn remove-from-all-rooms [state player-id]
  (let [old-room (get-in state [:users player-id :room])]
    (if old-room
      (-> state
          (update-in [:users player-id] dissoc :room)
          (update :rooms (fn [rooms]
                           (let [{:keys [admin players spectators]} (get rooms old-room)
                                 players' (disj players player-id)
                                 spectators' (disj spectators player-id)
                                 admin' (if (= admin player-id)
                                          (or (first (shuffle players'))
                                              (first (shuffle spectators')))
                                          admin)]
                             (if (nil? admin')
                               (dissoc rooms old-room)
                               (assoc rooms old-room {:admin admin' :players players'}))))))
      state)))

(comment
  (add-to-room {:rooms {"room1" {:admin "p" :players #{"p"}}} :users {}} "room1" "p2" :players)
  (remove-from-all-rooms {:rooms {"room1" {:admin "p" :players #{"p"}}} :users {"p" {:room "room1"}}} "p"))

;; FIXME these are terrible names
(defonce c (a/chan (a/sliding-buffer 100)))
(defn linearize! [f]
  (assert (>!! c f) "put should succeed"))

(defn join-room [state room player role]
  {:pre [state room player (contains? #{:players :spectators} role)]
   :post [(= (get-in state [:rooms :rooms]) nil)]}
  (-> state
      (remove-from-all-rooms player)
      (add-to-room room player role)))

(defn leave-room [rooms player]
  (remove-from-all-rooms rooms player))

(defn broadcast-update! [{:keys [rooms users]} room-id]
  {:pre [rooms users room-id]}
  (let [room (get rooms room-id)]
    (assert room)
    (doseq [player-id (shuffle (concat (:players room) (:spectators room)))
            :let [socket (get-in users [player-id :socket])]]
      (ws/send socket (html (room-html room-id player-id room))))))

(ns-unmap *ns* 'room-handler)
(defmulti room-handler request-type)
(defmethod room-handler :htmx [request]
  (let [room-id (:id (query-params request))
        player-id (:value (get (:cookies request) "id"))
        _ (assert (not (nil? player-id)) "player-id is nil!")
        rooms (:rooms @state)
        room (or (get rooms room-id) {:admin false :players #{} :spectators #{}})]
    (h/html (room-html room-id player-id room))))

(defmethod room-handler :default [request]
  (let [room-id (:id (query-params request))
        player-id (:value (get (:cookies request) "id"))
        _ (assert (not (nil? player-id)) "player-id is nil!")
        rooms (:rooms @state)
        room (or (get rooms room-id) {:admin false :players #{} :spectators #{}})]
    (page-skeleton [:div.container
                    [:h1.title "Captain Sonar"]
                    (room-html room-id player-id room)])))

(defn on-message [_socket message player-id]
  (let [{event "event" room-id "room"} (json/parse-string message)]
    (prn message)
    (case event
      "join-room" (linearize! (fn [] (let [state' (swap! state #(join-room % room-id player-id :players))]
                                       (broadcast-update! state' room-id))))
      "join-room-spec" (linearize! (fn [] (let [state' (swap! state #(join-room % room-id player-id :spectators))]
                                            (broadcast-update! state' room-id)))))))

(defn ws-handler [request]
  (if (ws/upgrade-request? request)
    (let [user-id (get-in request [:cookies "id" :value])]
      (assert user-id)
      {::ws/listener
       {:on-open (fn [socket]
                   (println (:remote-addr request) "connected")
                   ;(ws/send socket (html [:div#messages {:hx-swap-oob "beforeend"} [:div "Howdy!"]]))
                   (let [current-time (System/currentTimeMillis)]
                     (swap! state assoc-in [:users user-id] {:socket socket :last-ping current-time}))
                   (let [keep-alive (fn []
                                      (while (ws/open? socket)
                                        (ws/ping socket)
                                        (Thread/sleep 1000)))]
                     (future (keep-alive))))
        :on-pong (fn [_socket _buffer]
                   (println (:remote-addr request) "pong")
                   (let [current-time (System/currentTimeMillis)]
                     (swap! state assoc-in [:users user-id :last-ping] current-time)))
        :on-message #(on-message %1 %2 user-id)}})
        ;:on-close (fn [_socket _code _reason]
        ;            (println (:remote-addr request) "disconnected")
        ;            (println _reason)
        ;            (let [player (get-in request [:cookies "id" :value])]
        ;              (future ((fn []
        ;                         (Thread/sleep 5000)
        ;                         (let [last-ping (get-in @users [user-id :last-ping])
        ;                               current-time (System/currentTimeMillis)]
        ;                           (if (> (- current-time 3000) last-ping)
        ;                             (do
        ;                               (leave-room! player)
        ;                               (println "left"))
        ;                             (println "didn't leave")))))))}})
    {:status 400 :headers {"vary" "hx-request" "cache-control" "no-store"} :body "Websocket upgrade requests only!"}))

(defn index-handler [request]
  {:status 200
   :headers {"vary" "hx-request" "cache-control" "no-store"}
   :body (index-page request)})

(defn leave-room-handler [request]
  (let [player-id (get-in request [:cookies "id" :value])]
    (linearize! #(let [state' (swap! state (fn [state] (leave-room state player-id)))]
                   ;; FIXME this should be more granular to the specific room
                   (doseq [room-id (keys (:rooms state'))]
                     (broadcast-update! state' room-id))))
    {:status 200
     :headers {"vary" "hx-request"
               "cache-control" "no-store"
               "hx-redirect" "/"}
     :body nil}))

(defn app [request]
  (case [(:request-method request) (:uri request)]
    [:get "/"] (index-handler request)
    [:get "/index.css"] (file-rsp "resources/index.css")
    [:post "/"] {:status 200 :headers {"vary" "hx-request" "cache-control" "no-store"} :body "post"}
    [:get "/room"] {:status 200
                    :headers {"vary" "hx-request"
                              "cache-control" "no-store"}
                    :body (str (room-handler request))}
    [:get "/ws"] (ws-handler request)
    [:post "/leave-room"] (leave-room-handler request)
    {:status 404 :headers {"vary" "hx-request" "cache-control" "no-store"} :body (html [:h1 "404 Not Found"])}))

(defn make-id-cookie []
  {:value (str (random-uuid))
   :secure true
   :http-only true
   :same-site :strict
   :max-age 86400
   :path "/"})

(defn user-id-middleware [handler]
  (fn [request]
    (let [id (get-in request [:cookies "id" :value])]
      (if id
        (handler request)
        (let [cookie (make-id-cookie)
              request' (assoc-in request [:cookies "id"] cookie)
              response (handler request')]
          (assoc-in response [:cookies "id"] cookie))))))

(defn -main
  "Start here!"
  [& _args]
  (a/thread
    (loop []
      (let [f (<!! c)]
        (try (f)
             (catch Exception e (str "caught: " (.getMessage e))))
        (recur))))
  (jetty/run-jetty (middleware/wrap-cookies (user-id-middleware #(app %))) {:port 3000 :join? false}))

(comment
  (do
    (.stop server)
    (a/close! c)
    (def c (a/chan (a/sliding-buffer 100)))
    (reset! state {:rooms {} :users {}})
    (def server (-main))))

(comment
  (-main)
  (html [:h1.hi "hi"] [:h1 "hi"])
  (page/html5 [:h1 "hi"]))
