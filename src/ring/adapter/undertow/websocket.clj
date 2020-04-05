(ns ring.adapter.undertow.websocket
  (:refer-clojure :exclude [send])
  (:import
   [org.xnio Buffers]
   [java.nio ByteBuffer]
   [io.undertow.server HttpServerExchange]
   [io.undertow.websockets
    WebSocketConnectionCallback
    WebSocketProtocolHandshakeHandler]
   [io.undertow.websockets.core
    AbstractReceiveListener
    BufferedBinaryMessage
    BufferedTextMessage
    CloseMessage
    StreamSourceFrameChannel
    WebSocketChannel
    WebSockets]
   [io.undertow.websockets.spi WebSocketHttpExchange]
   [org.xnio ChannelListener]
   [ring.adapter.undertow Util]))

(defn ws-listener
  "Default websocket listener

   Takes a map of functions as opts:
   :on-message | fn taking map of keys :channel, :data
   :on-close   | fn taking map of keys :channel, :message
   :on-error   | fn taking map of keys :channel, :error

   Each key defaults to no action"
  [{:keys [on-message on-close on-close-message on-error]}]
  (let [on-message (or (constantly nil))
        on-error (or on-error (constantly nil))
        on-close-message (or on-close-message (constantly nil))
        on-close         (or on-close
                             (fn [{:keys [ws-channel]}]
                               (on-close-message {:channel ws-channel
                                                  :message (CloseMessage. CloseMessage/GOING_AWAY nil)})))]
    (proxy [AbstractReceiveListener] []
      (onFullTextMessage [^WebSocketChannel channel ^BufferedTextMessage message]
        (on-message {:channel channel
                     :data    (.getData message)}))
      (onFullBinaryMessage [^WebSocketChannel channel ^BufferedBinaryMessage message]
        (let [pooled (.getData message)]
          (try
            (let [payload (.getResource pooled)]
              (on-message {:channel channel
                           :data    (Util/toArray payload)}))
            (finally (.free pooled)))))
      (onClose [^WebSocketChannel websocket-channel ^StreamSourceFrameChannel channel]
        (on-close {:channel    channel
                   :ws-channel websocket-channel}))
      (onCloseMessage [^CloseMessage message ^WebSocketChannel channel]
        (on-close-message {:channel channel
                           :message message}))
      (onError [^WebSocketChannel channel ^Throwable error]
        (on-error {:channel channel
                   :error   error})))))

(defn ws-callback
  [{:keys [on-open listener]
    :or   {on-open (constantly nil)}
    :as   ws-opts}]
  (let [listener (if (instance? ChannelListener listener)
                   listener
                   (ws-listener ws-opts))]
    (reify WebSocketConnectionCallback
      (^void onConnect [_ ^WebSocketHttpExchange exchange ^WebSocketChannel channel]
        (on-open {:channel channel})
        (.set (.getReceiveSetter channel) listener)
        (.resumeReceives channel)))))

(defn ws-request [^HttpServerExchange exchange ^WebSocketConnectionCallback callback]
  (let [handler (WebSocketProtocolHandshakeHandler. callback)]
    (.handleRequest handler exchange)))

(defn send-text
  ([message channel] (send-text message channel nil))
  ([message channel callback]
   (WebSockets/sendText message channel callback))
  ([message channel callback timeout]
   (WebSockets/sendText message channel callback timeout)))

(defn send-binary
  ([message channel] (send-text message channel nil))
  ([message channel callback]
   (WebSockets/sendBinary message channel callback))
  ([message channel callback timeout]
   (WebSockets/sendBinary message channel callback timeout)))

(defn send
  ([message channel] (send message channel nil))
  ([message channel callback]
   (if (string? message)
     (send-text message channel callback)
     (send-binary message channel callback)))
  ([message channel callback timeout]
   (if (string? message)
     (send-text message channel callback timeout)
     (send-binary message channel callback timeout))))