#!/bin/bash
set -e

scriptdir=$(cd $(dirname $0); pwd -P)
cli=$scriptdir/cli.sh

# get the list of Bitcoin addresses associated with the Catena account
addrs=`$cli getaddressesbyaccount catena 2>/dev/null | jq '.[]' --raw-output`
if [ -n "$addrs" ]; then
    for addr in $addrs; do
        echo "Catena PK(s): $addr"
        
        privkey=`$cli dumpprivkey $addr`
        echo "Catena SK(s): $privkey"
        echo
    done
else
    echo "No Catena PKs available yet. Please run $scriptdir/gen-101-blocks.sh and $scriptdir/get-catena-funds.sh"
fi

# display accounts and unspent outputs
set -x
$cli listaccounts
$cli listunspent
