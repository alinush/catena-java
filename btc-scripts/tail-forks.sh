#!/bin/bash

set -e

scriptdir=$(cd $(dirname $0); pwd -P)

. $scriptdir/bitcoin-lib.sh

$cli getchaintips | jq .

i=1
for h in `get_tips`; do
    #echo "Fork: $i"
    i=$(($i+1))
    
    #$cli getblockheader $h
    #echo $h
    $scriptdir/tail-blocks.sh "$h"
done
