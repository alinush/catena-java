#!/bin/bash
set -e

scriptdir=$(cd $(dirname $0); pwd -P)
cli=$scriptdir/cli.sh

# generate 101 blocks so that the genesis block coinbase TX is spendable
$cli -rpcwait generate 101
