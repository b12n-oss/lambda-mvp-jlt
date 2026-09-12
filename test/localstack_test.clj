(ns localstack-test
  (:require [clojure.test :refer [deftest is run-tests]]))

(load-file "script/localstack.clj")

;; LAMBDA_ENDPOINT_URL is the single switch: unset/blank means real AWS
;; (existing behavior, untouched); set means every aws CLI call in this repo
;; goes to that endpoint instead, with LocalStack dummy credentials.

(deftest endpoint-reads-lambda-endpoint-url-from-env
  (is (= "http://localhost:4566"
         (script.localstack/endpoint {"LAMBDA_ENDPOINT_URL" "http://localhost:4566"})))
  (is (= "http://localhost:4566"
         (script.localstack/endpoint {"LAMBDA_ENDPOINT_URL" "  http://localhost:4566  "})))
  (is (nil? (script.localstack/endpoint {})))
  (is (nil? (script.localstack/endpoint {"LAMBDA_ENDPOINT_URL" ""})))
  (is (nil? (script.localstack/endpoint {"LAMBDA_ENDPOINT_URL" "   "}))))

(deftest with-endpoint-inserts-global-option-right-after-aws
  (is (= ["aws" "--endpoint-url" "http://localhost:4566" "lambda" "invoke"]
         (script.localstack/with-endpoint ["aws" "lambda" "invoke"] "http://localhost:4566")))
  (is (= ["aws" "sts" "get-caller-identity"]
         (script.localstack/with-endpoint ["aws" "sts" "get-caller-identity"] nil))))

(deftest with-endpoint-passes-non-aws-argv-through-unchanged
  ;; demo.clj's sh builds both aws and docker commands through one helper
  (is (= ["docker" "info"]
         (script.localstack/with-endpoint ["docker" "info"] "http://localhost:4566"))))

(deftest credentials-env-gives-localstack-dummies-only-when-endpoint-set
  (is (= {"AWS_ACCESS_KEY_ID" "test"
          "AWS_SECRET_ACCESS_KEY" "test"
          "AWS_DEFAULT_REGION" "us-east-1"}
         (script.localstack/credentials-env {} "http://localhost:4566")))
  ;; {} rather than nil: the sh helpers pass this straight to
  ;; babashka's :extra-env, which wants a map either way
  (is (= {} (script.localstack/credentials-env {} nil)))
  (is (= {} (script.localstack/credentials-env {} ""))))

(deftest credentials-env-lets-caller-set-values-win
  (is (= {"AWS_ACCESS_KEY_ID" "keep-me"
          "AWS_SECRET_ACCESS_KEY" "test"
          "AWS_DEFAULT_REGION" "eu-west-1"}
         (script.localstack/credentials-env {"AWS_ACCESS_KEY_ID" "keep-me"
                                             "AWS_DEFAULT_REGION" "eu-west-1"}
                                            "http://localhost:4566"))))

(deftest docker-run-argv-runs-detached-name-exposes-edge-and-docker-socket
  (let [argv (script.localstack/docker-run-argv {})]
    (is (= "docker" (first argv)))
    (is (= ["--name" script.localstack/container-name]
           (->> argv
                (partition 2 1)
                (some (fn [[k v]] (when (= k "--name") [k v]))))))
    (is (some #(= "-p" %) argv))
    (is (some #{"4566:4566"} argv))
    ;; the Lambda executor needs the host docker daemon to run function containers
    (is (some #(= "/var/run/docker.sock:/var/run/docker.sock" %) argv))
    (is (= script.localstack/image (last argv)))))

(deftest docker-run-argv-honors-image-override-and-auth-token
  ;; LocalStack >= 2026.3 requires LOCALSTACK_AUTH_TOKEN even for community
  ;; features; the default image is pinned to the last tokenless tag.
  (let [argv (script.localstack/docker-run-argv
              {"LOCALSTACK_IMAGE" "localstack/localstack:latest"
               "LOCALSTACK_AUTH_TOKEN" "ls-abc"})]
    (is (= "localstack/localstack:latest" (last argv)))
    (is (= ["-e" "LOCALSTACK_AUTH_TOKEN=ls-abc"]
           (->> argv (partition 2 1) (some (fn [[k v]] (when (= k "-e") [k v])))))))
  (is (nil? (->> (script.localstack/docker-run-argv {})
                 (partition 2 1)
                 (some #(= (first %) "-e"))))))

(let [{:keys [fail error]} (run-tests 'localstack-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
