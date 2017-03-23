#!/bin/bash
set -e

#export MAVEN_OPTS="-ea"
scriptdir=$(cd $(dirname $0); pwd -P)

$scriptdir/run-server.sh -n testnet $@
