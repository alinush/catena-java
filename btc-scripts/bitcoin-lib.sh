#!/bin/bash

[ -z "$scriptdir" ] && { echo "ERROR: You must define \$scriptdir before importing bitcoin-lib.sh. Exiting..."; exit 1; }

# make sure cli is defined
cli=${cli:-$scriptdir/cli.sh}

which jq &>/dev/null || { echo "ERROR: 'jq' is not installed. Exiting..."; exit 1; }

generate()
{
    if [ $# -ne 1 ]; then
        echo "ERROR: You must specify the number of blocks to generate."
        exit 1
    fi
    
    if [ $1 -lt 1 ]; then
        echo "ERROR: The number of blocks to generate must be greater than 0."
        exit 1
    fi

    $cli generate $1
}

invalidate_block()
{
    $cli invalidateblock "$1"
}

reconsider_block()
{
    $cli reconsiderblock "$1"
}

get_tip()
{
    $cli getbestblockhash
}

get_tips()
{
    $cli getchaintips | jq '.[] | .hash' --raw-output
}

get_block_height()
{
    $cli getblockheader "$1" | jq '.height' --raw-output
}

get_prev_hash()
{
    $cli getblockheader "$1" | jq '.previousblockhash' --raw-output
}
