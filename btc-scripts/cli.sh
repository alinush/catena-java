#!/bin/bash
which bitcoin-cli &>/dev/null || { echo "ERROR: 'bitcoin-cli' is not installed. Exiting..."; exit 1; }

scriptdir=$(cd $(dirname $0); pwd -P)
port=8332

#[ -n "$BTC_CLI_EXTRA_ARGS" ] && echo "./cli.sh extra args: $BTC_CLI_EXTRA_ARGS"

# if bitcoind is running as a daemon, caller should pass in "-rpcwait" so as to wait for bitcoind to start
bitcoin-cli -regtest -rpcport=$port -rpcuser=yourrpcuser -rpcpassword=yourrpcpass $BTC_CLI_EXTRA_ARGS $@
