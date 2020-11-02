# timbre-json-appender

[![CircleCI](https://circleci.com/gh/viesti/timbre-json-appender.svg?style=svg)](https://circleci.com/gh/viesti/timbre-json-appender) [![Clojars Project](https://img.shields.io/clojars/v/viesti/timbre-json-appender.svg)](https://clojars.org/viesti/timbre-json-appender)

A structured log appender for [Timbre](https://github.com/ptaoussanis/timbre) using [jsonista](https://github.com/metosin/jsonista).

Makes extracting data from logs easier in for example [AWS CloudWatch
Logs](https://aws.amazon.com/about-aws/whats-new/2015/01/20/amazon-cloudwatch-logs-json-log-format-support/) and [GCP Stackdriver Logging](https://cloud.google.com/logging/).

A Timbre log invocation maps to JSON messages the following way:

```clojure
(timbre/error (IllegalStateException. "Not logged in") "Action failure" :user-id 1)
=>
{"timestamp": "2019-07-03T10:00:08Z", # Always included
 "level": "error",                    # ditto
 "thread": nRepl-session-...",        # ditto
 "msg": "Action failure",             # Included when logging call contains a single argument, or odd number of arguments
 "args: {"user-id": 1},               # All arguments that follow the first argument
 "err": {"via":[{"type":"...,         # When exception is logged, a Throwable->map presentation of the exception
 "ns": "user",                        # Included when exception is logged
 "file": "...",                       # ditto
 "line": "..."}                       # ditto
```

## Usage

```clojure
user> (require '[timbre-json-appender.core :as tas])
user> (tas/install) ;; Set json-appender as sole Timbre appender
user> (require '[taoensso.timbre :as timbre])
user> (timbre/info "Hello" :user-id 1 :profile {:role :tester})
{"timestamp":"2019-07-03T10:00:08Z","level":"info","thread":"nRepl-session-97b9389e-a563-4f0d-8b8a-f58050297092","msg":"Hello","args":{"user-id":1,"profile":{"role":"tester"}}}
user> (tas/install {:pretty true}) ;; For repl only
user> (timbre/info "Hello" :user-id 1 :profile {:role :tester})
{
  "timestamp" : "2019-07-03T10:23:38Z",
  "level" : "info",
  "thread" : "nRepl-session-97b9389e-a563-4f0d-8b8a-f58050297092",
  "msg" : "Hello",
  "args" : {
    "user-id" : 1,
    "profile" : {
      "role" : "tester"
    }
  }
}
```

Exceptions are included in `err` field via `Throwable->map` and contain `ns`, `file` and `line` fields:

```clojure
user> (tas/install)
user> (timbre/info (IllegalStateException. "Not logged in") "Hello" :user-id 1 :profile {:role :tester})
(timbre/info (IllegalStateException. "Not logged in") "Hello" :user-id 1 :profile {:role :tester})
{"args":{"user-id":1,"profile":{"role":"tester"}},"ns":"user","file":"*cider-repl home/timbre-json-appender:localhost:49943(clj)*","line":523,"err":{"via":[{"type":"java.lang.IllegalStateException","message":"Not logged in","at":["user$eval11384$fn__11385","invoke","NO_SOURCE_FILE",523]}],"trace":[["user$eval11384$fn__11385","invoke","NO_SOURCE_FILE",523],["clojure.lang.Delay","deref","Delay.java",42],["clojure.core$deref","invokeStatic","core.clj",2320],["clojure.core$deref","invoke","core.clj",2306]
```

Data that isn't serializable is omitted, to not prevent logging:

```clojure
user> (tas/install {:pretty true}) ;; For repl only
user> (timbre/info "Hello" :o (Object.))
{
  "timestamp" : "2019-07-03T10:26:38Z",
  "level" : "info",
  "thread" : "nRepl-session-97b9389e-a563-4f0d-8b8a-f58050297092",
  "msg" : "Hello",
  "args" : {
    "o" : { }
  }
}
```

Arguments can also be placed inline, instead of being put behind `:args` key.

```
user> (tas/install {:inline-args? true})
user> (timbre/info "Hello" :role :admin)
{"timestamp":"2020-09-18T20:26:59Z","level":"info","thread":"nREPL-session-0ac148ff-e0c2-4578-ac64-e5411de14d1f","msg":"Hello","role":"admin"}
nil
```

As a last resort, default println appender is used, if JSON serialization fails.

# Changelog

2020-11-02 (0.1.1)

* Support inlining arguments

2020-08-13

* Use taoensso.timbre/println-appender as fallback if JSON serialization fails

2019-08-11

* Create object mapper only once (improves performance)
* Support format string style log formatting

2019-07-03

* Initial release
