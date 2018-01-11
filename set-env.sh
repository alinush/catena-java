#!/bin/bash

#called=$_
#[[ $called != $0 ]] && echo "Script is being sourced" || echo "Script is being run"
#echo "\$BASH_SOURCE ${BASH_SOURCE[@]}"
#echo "${BASH_SOURCE[@]}" 
#if [ -z "${BASH_SOURCE[@]}" ]; then
#    echo "ERROR: Could not get the path to the set-env.sh script."
#    exit 1
#fi

#scriptdir=$(cd $(dirname ${BASH_SOURCE[@]}); pwd -P)

scriptdir=$(cd $(dirname $0); pwd -P)
echo "set-env.sh script directory: $scriptdir"

# export a variable that indicates that Catena environment is setup
export CATENA_ENV=1

# add Bitcoin-related scripts to PATH; we need easy access to them
btcscripts=btc-scripts
if ! echo "$PATH" | grep "$scriptdir/$btcscripts" >/dev/null; then
    export PATH="$scriptdir/$btcscripts:$PATH"
    echo "Set \$PATH to $PATH..."
else
    echo "\$PATH already set to $PATH"
fi

if [ "$(uname -s)" = "Linux" ]; then
    echo "Linux detected... "
    btc_srcs=/usr/bin/
    if [ -d "$btc_srcs" -a -f "$btc_srcs/bitcoind" ]; then
        export PATH="$PATH:$btc_srcs"
        echo "Added bitcoind compiled binaries in '$btc_srcs' to \$PATH."
    else
        echo
        echo "ERROR: No bitcoind detected in '$btc_srcs'"
        echo "Please compile bitcoind in that location and retry!"
        exit 1
    fi
fi

if [ "$(uname -s)" = "Darwin" ]; then
    echo "Mac OS X detected... "
    btc_srcs=$HOME/repos/bitcoin/src
    if [ -d "$btc_srcs" -a -f "$btc_srcs/bitcoind" ]; then
        export PATH="$PATH:$btc_srcs"
        echo "Added bitcoind compiled binaries in '$btc_srcs' to \$PATH."
    else
        echo
        echo "ERROR: No bitcoind detected in '$btc_srcs'"
        echo "Please compile bitcoind in that location and retry!"
        exit 1
    fi
fi

# ignore .git directory, always display line numbers, etc.
alias grep='grep -In --color=auto --exclude-dir=.git'
