#!/bin/bash

scriptdir=$(cd $(dirname $0); pwd -P)
port=8332          # NOTE: -rpcport does NOT default to 8332
gen101=0           # if set to 1, then will generate 101 blocks for
daemonize=         # set to -daemon when starting bitcoind as a daemon
datadir=
network=-regtest
printtoconsole=
bloom=1
cmd=bitcoind

# parse command line args (first colon (:) disables verbose mode, second colon requires an argument)
# (see more info here about getopts: http://wiki.bash-hackers.org/howto/getopts_tutorial)
while getopts ":b:dgqtv" opt; do
    case $opt in
        b)
            bloom=$OPTARG
            ;;
        d)
            echo "Will daemonize bitcoind..."
            daemonize="-daemon"
            ;;
        g)
            echo "Will generate first 101 blocks, if need be..."
            gen101=1
            ;;
        q)
            cmd=bitcoin-qt
            ;;
        t)
            echo "Will connect to testnet, instead of starting regtest instance."
            network=-testnet
            ;;
        v)
            printtoconsole=-printtoconsole
            ;;
        \?)
            echo "Invalid option: -$OPTARG" >&2
            exit 1
            ;;
        :)
            echo "Option -$OPTARG requires an argument." >&2
            exit 1
            ;;
    esac
done

# shift away the processed 'getopts' args
shift $((OPTIND-1))
# rest of 'mass-arguments' or 'operands', if needed, are in $@
datadir=$1
shift 1

if [ -z "$datadir" ]; then
    echo "Usage: $0 [OPTIONS] <datadir>"
    echo
    echo "OPTIONS:"
    echo " -b=0 no bloom filtering"
    echo " -d   daemonizes"
    echo " -g   generates the first 101 blocks for regtest mode"
    echo " -q   start with Qt GUI"
    echo " -t   connect to testnet instead of regtest"
    echo " -v   print verbose output to console"
    exit 1
fi

# check if there was a previous run, so that if '-g' was passed we know not to
# regenerate the first 101 blocks
prevRun=0
if [ $gen101 -eq 1 -a -d "$datadir" ]; then
    echo "Previous run detected. Won't generate first 101 blocks."
    prevRun=1
fi

# create data dir, if not existing
mkdir -p "$datadir"

# check that previous run was cleaned up properly
#pidfile=$datadir/regtest/bitcoind.pid # WARNING: For some reason, bitcoind only accepts the pidfile's name, not full path
pidfile=bitcoind.pid
if [ -f $pidfile ]; then
    echo
    echo "ERROR: pidfile '$pidfile' already exists, which means bitcoind could still be running. Please inspect 'ps -ef' below..."
    echo
    ps -ef | grep bitcoind
    echo
    exit 1
fi

# make the first 50 coin reward spendable by generating 101 blocks, if no previous run
# issue the command before starting bitcoind with -rpcwait as a parameter to bitcoin-cli
if [ $gen101 -eq 1 -a $prevRun -eq 0 ]; then
    # wait for RPC server to start before sending commands (send this cmd to background)
    BTC_CLI_EXTRA_ARGS="-rpcwait" $scriptdir/gen-101-blocks.sh &
fi

echo "Launching bitcoind..."
$cmd -peerbloomfilters=$bloom -txindex -debug=1 $daemonize -datadir="$datadir" -server $network -listenonion=0 -keypool=10 -listen -rpcport=$port -rpcuser=yourrpcuser -rpcpassword=yourrpcpass -pid="$pidfile" -acceptnonstdtxn=0 $printtoconsole $*
