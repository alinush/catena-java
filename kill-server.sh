#!/bin/bash
set -e

scriptdir=$(cd $(dirname $0); pwd -P)

pid=`ps -ef | pgrep --full "org.catena.server.ServerApp" || :`

if [ -z "$pid" ]; then
    echo "ERROR: Catena server is NOT currently running..."
    exit 1
fi

echo "Killing softly..."
kill $pid
sleep 2
echo "Killing!"
kill -9 $pid
