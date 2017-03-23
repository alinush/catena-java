#!/bin/bash
set -e

scriptdir=$(cd $(dirname $0); pwd -P)
datadir=$1

if [ -z "$datadir" ]; then
    echo "Usage: $0 <datadir>"
    exit 1
fi

pidfile=$datadir/regtest/bitcoind.pid

if [ ! -f $pidfile ]; then
    echo "ERROR: No PID file at '$pidfile'"
    exit 1
fi

echo "Killing bitcoind at PID `cat $pidfile`" 

pkill -F $pidfile
