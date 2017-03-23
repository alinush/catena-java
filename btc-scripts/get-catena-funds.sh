#!/bin/bash
set -e

# NOTE: This script redirects all output to stderr, so that the JUnit code
# can read only the outputted private key from stdout

scriptdir=$(cd $(dirname $0); pwd -P)

. $scriptdir/bitcoin-lib.sh 1>&2

# directory where the priv.key file with the Catena funds will be stored
cli=$scriptdir/cli.sh

tip=`get_tip`
num_blocks=`get_block_height $tip`
echo "Number of blocks: $num_blocks" 1>&2

if [ $num_blocks -lt 101 ]; then
    echo "ERROR: Cannot spend any coinbase TXs because we need at least 101 blocks in the chain. We have only $num_blocks." 1>&2
    exit 1
fi

# generate a new address for the Catena account
addr=`$cli getaccountaddress catena`

# dump the private key of the Catena account to a file so the Catena server can use it
privkey=`$cli dumpprivkey $addr`

# log some info for the user
echo "Public key address: $addr" 1>&2
echo "Private key: $privkey" 1>&2

# send some funds to the Catena private key used by the Catena service
$cli sendtoaddress $addr 25 "initialize-Catena-app-funds" "" true 1>&2
$cli generate 1 1>&2

# print to stdout the private key file (used by our JUnit tests)
echo $privkey
