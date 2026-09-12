(ns script.localstack
  "Pure helpers for running the demo against LocalStack instead of real AWS.
   No I/O here -- see script/localstack_run.clj for the orchestration.

   The single switch is LAMBDA_ENDPOINT_URL: unset (or blank) means real AWS
   and every helper here is a no-op; set means every aws CLI call made by
   this repo's scripts is redirected to that endpoint (normally LocalStack's
   http://localhost:4566) with LocalStack's dummy credentials, so nothing
   touches a real account."
  (:require [clojure.string :as str]))

(def image "localstack/localstack:4.13.1")

(def container-name "lambda-mvp-jlt-localstack")

(def default-endpoint "http://localhost:4566")

(defn endpoint
  "The override endpoint from LAMBDA_ENDPOINT_URL (trimmed), or nil when unset
   or blank -- nil meaning real AWS, the repo's default behavior."
  [env]
  (some-> (get env "LAMBDA_ENDPOINT_URL")
          str/trim
          not-empty))

(defn with-endpoint
  "Insert --endpoint-url as an aws CLI global option (must sit before the
   service subcommand) when endpoint is non-nil; otherwise argv unchanged.
   Non-aws argv (docker, bb, ...) passes through untouched, so a sh helper
   that builds mixed commands can call this unconditionally."
  [argv endpoint]
  (if (and endpoint (= "aws" (first argv)))
    (into ["aws" "--endpoint-url" endpoint] (rest argv))
    argv))

(defn credentials-env
  "Env overrides for LocalStack's documented dummy credentials. Caller-supplied
   values win, so an explicit AWS_DEFAULT_REGION (or a real profile's
   credentials) is never silently clobbered. Returns nil when endpoint is nil
   (real AWS -- leave the environment alone)."
  [env endpoint]
  (if (some-> endpoint str/trim not-empty)
    (merge {"AWS_ACCESS_KEY_ID" "test"
            "AWS_SECRET_ACCESS_KEY" "test"
            "AWS_DEFAULT_REGION" "us-east-1"}
           (select-keys env ["AWS_ACCESS_KEY_ID" "AWS_SECRET_ACCESS_KEY"
                             "AWS_DEFAULT_REGION" "AWS_REGION"]))
    {}))

(defn docker-run-argv
  "docker run argv for a LocalStack container: detached, named, edge port
   4566 published, and the docker socket mounted so LocalStack's Lambda
   provider can run function containers through the host daemon (the standard
   localstack CLI setup; without the mount, lambda emits errors about
   reaching the docker daemon).

   The default image is pinned to the last tag that runs without a license:
   localstack/localstack >= 2026.3 (including latest) exits 55 at startup
   unless LOCALSTACK_AUTH_TOKEN is set -- pass one via env and/or override the
   image with LOCALSTACK_IMAGE to use a newer LocalStack."
  ([] (docker-run-argv (System/getenv)))
  ([env]
   (let [auth-token (some-> (get env "LOCALSTACK_AUTH_TOKEN") str/trim not-empty)
         image (or (some-> (get env "LOCALSTACK_IMAGE") str/trim not-empty)
                   image)]
     (vec (concat ["docker" "run" "-d" "--name" container-name
                  "-p" "4566:4566"
                  "-v" "/var/run/docker.sock:/var/run/docker.sock"]
                  (when auth-token ["-e" (str "LOCALSTACK_AUTH_TOKEN=" auth-token)])
                  [image])))))

(let [required {"LAMBDA_ENDPOINT_URL" default-endpoint
                "AWS_ACCESS_KEY_ID" "test"
                "AWS_SECRET_ACCESS_KEY" "test"
                "AWS_DEFAULT_REGION" "us-east-1"}]
  (defn demo-env
    "Environment map for running this repo's own bb tasks (deploy/invoke/
     teardown) against LocalStack. Computed at load like the env-reading
     defaults in the other scripts."
    []
    (merge required
           (select-keys (System/getenv)
                        ["AWS_DEFAULT_REGION" "AWS_REGION" "LAMBDA_MVP_FUNCTION_NAME"]))))

