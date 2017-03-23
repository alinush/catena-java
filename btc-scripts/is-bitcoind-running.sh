#!/bin/bash
set -e

scriptdir=$(cd $(dirname $0); pwd -P)
datadir=$1

if [ -z "$datadir" ]; then
    echo "Usage: $0 <datadir>"
    out=`ps -ef | grep bitcoind | grep -v "grep bitcoind" || :`
    echo
    echo "FYI, all bitcond instances (from 'ps -ef | grep bitcoind' output):"
    echo "$out"
    exit 1
fi

pidfile=$datadir/bitcoind.pid

if [ ! -f $pidfile ]; then
    echo "ERROR: No PID file at '$pidfile'"
    exit 1
fi

pgrep -F $pidfile
