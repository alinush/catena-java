#!/bin/bash

set -e

##
## Forks the chain into two forks A and B, with fork B being longer than A
## 

scriptdir=$(cd $(dirname $0); pwd -P)

. $scriptdir/bitcoin-lib.sh

size=${1:-2}

# Fork A: get tip and height
oldtip=`get_tip`
prevhash=`get_prev_hash $oldtip`
height=`get_block_height $oldtip`

t() {
    echo "${1:0:8}..."
}

if [ $height -lt 101 ]; then
    echo "ERROR: Will not fork unless you generate the first 101 blocks. Blockchain height is $height right now."
    exit 1
fi

# Fork A:
#  -> b -> b -> A    ( valid fork A, main chain )
echo "Fork A: Block hash at height $height (tip): $(t $oldtip) (prev $(t $prevhash))"

# Fork A: fork before the tip, meaning the tip will be part of the invalid fork
# Forks:
#
#  -> b -> b -       ( valid fork B, main chain )
#            |
#            \-> A   ( invalid fork A )
#
invalidate_block $oldtip
echo "Invalidated fork A tip at height $height: $(t $oldtip)" 

# Fork B: generate 'size' blocks in fork B (these new blocks should include the TXs from block A)
# Forks:
#
#  -> b -> b --> B -> B -> ... -> B  ( valid fork B, main chain )
#            |                         
#            \-> A                   ( invalid fork A )
#
echo "Fork B: Generated $size new blocks!"
generate $size

# Fork A: "Make fork A valid again!"
# Forks:
#
#  -> b -> b --> B -> B -> ... -> B  ( valid fork B, main chain )
#            |                         
#            \-> A                   ( valid fork A )
#
echo "Revalidated fork A tip $(t $oldtip)"
reconsider_block $oldtip

newtip=`get_tip`
prevhash=`get_prev_hash $newtip`
height=`get_block_height $newtip`
echo "Fork B: Block hash at height $height (tip): $(t $newtip) (prev $(t $prevhash))"
