(ns script.demo
  "`jolt demo`: build, deploy and invoke the demo handler in one go. Every
  check that can fail runs first (tools on PATH, AWS credentials and region,
  Docker access, emulation for the target architecture), so a missing piece
  stops the run before a multi-minute build or any change to the AWS account.
  The steps themselves are the ordinary `image`, `deploy` and `invoke` tasks."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def arch (or (System/getenv "LAMBDA_ARCH") "arm64"))
(def base-image (or (System/getenv "BASE_IMAGE") "public.ecr.aws/amazonlinux/amazonlinux:2023"))

(defn- sh [& args]
  (let [{:keys [exit out err]}
        (apply p/shell {:out :string :err :string :continue true
                        :extra-env (script.localstack/credentials-env
                                    (System/getenv) localstack-endpoint)}
               (script.localstack/with-endpoint args localstack-endpoint))]
    {:exit exit :out out :err err}))

(defn- die! [& msg]
  (binding [*out* *err*] (apply println "lambda-mvp-jlt:" msg))
  (System/exit 1))

(defn- require-tools! []
  (let [missing (remove fs/which ["docker" "aws"])]
    (when (seq missing)
      (die! "not on PATH:" (str/join ", " missing)
            "-- see Prerequisites in docs/guide/getting-started.md."))))

(defn- require-aws! []
  (if localstack-endpoint
    (println "lambda-mvp-jlt: LAMBDA_ENDPOINT_URL set -- using" localstack-endpoint
             "; skipping the real-AWS credential/region checks")
    (require-real-aws!)))

(defn- require-real-aws! []
  (let [region (some not-empty [(System/getenv "AWS_REGION")
                                (System/getenv "AWS_DEFAULT_REGION")
                                (str/trim (:out (sh "aws" "configure" "get" "region")))])]
    (when-not region
      (die! "no AWS region set. Run `aws configure set region <region>` or set AWS_REGION, then retry."))
    (let [{:keys [exit out err]} (sh "aws" "sts" "get-caller-identity" "--output" "json")]
      (when-not (zero? exit)
        (die! "aws CLI has no usable credentials."
              "Run `aws configure` (or `aws configure sso`), or set AWS_PROFILE, then retry.\n"
              (str/trim (or err ""))))
      (let [{:strs [Account Arn]} (json/parse-string out)]
        (println "lambda-mvp-jlt: will deploy to account" Account "in" region "as" Arn)))))

(defn- require-docker! []
  (let [{:keys [exit err]} (sh "docker" "info")]
    (when-not (zero? exit)
      (if (str/includes? err "permission denied")
        (die! "this user can't reach the Docker daemon (permission denied)."
              "Add yourself to the docker group once (`sudo usermod -aG docker $USER`, then log out and back in) and retry.")
        (die! "Docker isn't reachable:\n" (str/trim err))))))

(defn- require-platform!
  "Runs a no-op in the base image under the target platform. That pulls the
  image (the build needs it anyway) and hits the same `exec format error` a
  build without an emulator would, in seconds instead of mid-build."
  []
  (let [platform (case arch
                   "arm64" "linux/arm64"
                   ("x86_64" "amd64") "linux/amd64"
                   (die! "LAMBDA_ARCH must be arm64 or x86_64, got" arch))
        native (case (System/getProperty "os.arch") "amd64" "x86_64" "aarch64" "arm64" nil)]
    (println "lambda-mvp-jlt: checking Docker can run" platform "containers (pulls the base image the first time)")
    (let [{:keys [exit err]} (sh "docker" "run" "--rm" "--platform" platform base-image "true")]
      (when-not (zero? exit)
        (if (str/includes? err "exec format error")
          (die! "Docker can't run" platform "containers here: no emulator registered."
                (if native (str "Build for this machine instead (LAMBDA_ARCH=" native " jolt demo)") "Build for this machine's architecture")
                "or register one: docker run --privileged --rm tonistiigi/binfmt --install" (subs platform 6))
          (die! "could not run" base-image "for" (str platform ":\n") (str/trim err)))))))

(defn- step! [task]
  (println (str "\nlambda-mvp-jlt: == " task " =="))
  (when-not (zero? (:exit (p/shell {:continue true} "bb" task)))
    (die! task "failed; stopping.")))

(require-tools!)
(require-aws!)
(require-docker!)
(require-platform!)
(run! step! ["image" "deploy" "invoke"])
(println "\nlambda-mvp-jlt: done. `jolt invoke` calls it again; `jolt teardown` deletes the function and role.")
