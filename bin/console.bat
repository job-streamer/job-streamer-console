@echo off

pushd %0\..\..

set /p VERSION=<VERSION

java -cp dist\job-streamer-console-%VERSION%.jar;"lib\*" clojure.main -m job-streamer.console.main

pause
