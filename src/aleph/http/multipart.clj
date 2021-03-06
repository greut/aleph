(ns aleph.http.multipart
  (:require
    [clojure.core :as cc]
    [byte-streams :as bs]
    [aleph.http.encoding :refer [encode]]
    [aleph.netty :as netty])
  (:import
    [java.util
     Locale]
    [java.io
     File]
    [java.nio
     ByteBuffer]
    [java.nio.charset
     Charset]
    [java.net
     URLConnection]
    [io.netty.util.internal
     ThreadLocalRandom]
    [io.netty.handler.codec.http
     DefaultHttpRequest
     FullHttpRequest
     HttpConstants]
    [io.netty.handler.codec.http.multipart
     HttpPostRequestEncoder
     MemoryAttribute]))

(defn boundary []
  (-> (ThreadLocalRandom/current) .nextLong Long/toHexString .toLowerCase))

(defn mime-type-descriptor
  [^String mime-type ^String encoding]
  (str
   (-> (or mime-type "application/octet-stream") .trim (.toLowerCase Locale/US))
   (when encoding
     (str "; charset=" encoding))))

(defn populate-part
  "Generates a part map of the appropriate format"
  [{:keys [part-name content mime-type charset transfer-encoding name]}]
  (let [file? (instance? File content)
        mt (or mime-type
             (when file?
               (URLConnection/guessContentTypeFromName (.getName ^File content))))
        ;; populate file name when working with file object
        filename (or name (when file? (.getName ^File content)))
        ;; use "name" as a part name when the last is not provided
        part-name-to-use (or part-name name filename)]
    {:part-name part-name-to-use
     :content (bs/to-byte-buffer content)
     :mime-type (mime-type-descriptor mt charset)
     :transfer-encoding transfer-encoding
     :name filename}))

;; Omit "content-transfer-encoding" when not provided
;;
;; RFC 2388, section 3:
;; Each part may be encoded and the "content-transfer-encoding" header
;; supplied if the value of that part does not conform to the default
;; encoding.
;;
;; Include local filename when provided. It might be required by a server
;; when dealing with users' file uploads.
;;
;; RFC 2388, section 4.4:
;; The original local file name may be supplied as well...
;;
;; Note, that you can use transfer-encoding=nil or :binary to leave data "as is".
;; transfer-encoding=nil omits "Content-Transfer-Encoding" header.
(defn part-headers [^String part-name ^String mime-type transfer-encoding name]
  (let [cd (str "Content-Disposition: form-data; name=\"" part-name "\""
             (when name (str "; filename=\"" name "\""))
             "\r\n")
        ct (str "Content-Type: " mime-type "\r\n")
        cte (if (nil? transfer-encoding)
              ""
              (str "Content-Transfer-Encoding: " (cc/name transfer-encoding) "\r\n"))]
    (bs/to-byte-buffer (str cd ct cte "\r\n"))))

(defn encode-part
  "Generates the byte representation of a part for the bytebuffer"
  [{:keys [part-name content mime-type charset transfer-encoding name] :as part}]
  (let [headers (part-headers part-name mime-type transfer-encoding name)
        body (bs/to-byte-buffer (if (some? transfer-encoding)
                                  (encode content transfer-encoding)
                                  content))
        header-len (.limit ^ByteBuffer headers)
        size (+ header-len (.limit ^ByteBuffer body))
        buf (ByteBuffer/allocate size)]
    (doto buf
      (.put ^ByteBuffer headers)
      (.put ^ByteBuffer body)
      (.flip))))

(defn
  ^{:deprecated "use aleph.http.multipart/encode-request instead"}
  encode-body
  ([parts]
    (encode-body (boundary) parts))
  ([^String boundary parts]
    (let [b (bs/to-byte-buffer (str "--" boundary))
          b-len (+ 6 (.length boundary))
          ps (map #(-> % populate-part encode-part) parts)
          boundaries-len (* (inc (count parts)) b-len)
          part-len (reduce (fn [acc ^ByteBuffer p] (+ acc (.limit p))) 0 ps)
          buf (ByteBuffer/allocate (+ 2 boundaries-len part-len))]
      (.put buf b)
      (doseq [^ByteBuffer part ps]
        (.put buf (bs/to-byte-buffer "\r\n"))
        (.put buf part)
        (.put buf (bs/to-byte-buffer "\r\n"))
        (.flip b)
        (.put buf b))
      (.put buf (bs/to-byte-buffer "--"))
      (.flip buf)
      (bs/to-byte-array buf))))

(defn encode-request [^DefaultHttpRequest req parts]
  (let [^HttpPostRequestEncoder encoder (HttpPostRequestEncoder. req true)]
    (doseq [{:keys [part-name content mime-type charset name]} parts]
      (if (instance? File content)
        (let [filename (.getName ^File content)
              name (or name filename)
              mime-type (or mime-type
                            (URLConnection/guessContentTypeFromName filename))
              content-type (mime-type-descriptor mime-type charset)]
          (.addBodyFileUpload encoder
                              part-name
                              name
                              content
                              content-type
                              false))
        (let [^Charset charset (cond
                                 (nil? charset)
                                 HttpConstants/DEFAULT_CHARSET

                                 (string? charset)
                                 (Charset/forName charset)

                                 (instance? Charset charset)
                                 charset)
              attr (if (string? content)
                     (MemoryAttribute. ^String part-name ^String content charset)
                     (doto (MemoryAttribute. ^String part-name charset)
                       (.addContent (netty/to-byte-buf content) true)))]
          (.addBodyHttpData encoder attr))))
    (let [req' (.finalizeRequest encoder)]
      [req' (when (.isChunked encoder) encoder)])))
