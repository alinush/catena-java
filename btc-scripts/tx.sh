#!/bin/bash

if [ $# -lt 1 ]; then
    echo "Usage: $0 <txid>"
    exit 1
fi

set -e

scriptdir=$(cd $(dirname $0); pwd -P)
cli=$scriptdir/cli.sh

tx=`$cli getrawtransaction $1`

$cli decoderawtransaction "$tx"
