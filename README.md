# job-streamer-console
TEST

This is a management console for job-streamer. It has the following features:

* Create or edit a job
* Schedule a job
* Monitor a job
* View the timeline of jobs
* Monitor agents

## Setup

Compile clojurescript files.

```
% lein cljsbuild auto
```

And start a ring server.

```
% lein ring server
```
