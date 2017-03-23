#!/bin/bash

if [ $# -lt 1 -a "$1" == "-h" ]; then
    echo "Usage: $0 [<blockhash>] [<num>]"
    echo
    echo "Prints the block hashes of the last 'num' blocks, including the specified block's hash."
    echo "If 'blockhash' is not specified, uses the best chain tip."
    echo "'num' defaults to 5."
    exit 1
fi

set -e

scriptdir=$(cd $(dirname $0); pwd -P)

. $scriptdir/bitcoin-lib.sh

currhash=${1:-`get_tip`}
currheight=`get_block_height $currhash`
num=${2:-5}

echo
echo "Printing last $num block hashes starting from block $currhash at height $currheight ..."
echo

for i in `seq 1 $num`; do
    if [ $currheight -lt 0 ]; then
        echo
        echo "Oops, reached the genesis block. Looks like we're done early!";
        break
    fi
    printf "%3s -> $currhash\n" $currheight
    currhash=`get_prev_hash $currhash`
    currheight=$(($currheight-1))
done
