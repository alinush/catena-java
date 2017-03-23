#!/bin/bash
set -e

#export MAVEN_OPTS="-ea"
scriptdir=$(cd $(dirname $0); pwd -P)
conffile=
btc_env=regtest
datadir=$btc_env-client
txid=
pubkey=

# parse command line args (first colon (:) disables verbose mode, second colon means the option requires an argument)
# (see more info here about getopts: http://wiki.bash-hackers.org/howto/getopts_tutorial)
while getopts ":hd:t:p:n:" opt; do
    case $opt in
        d)
            datadir=$OPTARG
            ;;
        t)
            txid=$OPTARG
            ;;
        n)
            btc_env=$OPTARG
            ;;
        p)
            pubkey=$OPTARG
            ;;
        h)
            echo "Usage: $0 -b"
            echo
            echo "OPTIONS:"
            echo
            echo "  -d <datadir>   stores the Catena server's data (blockchain, wallet) in the specified directory"
            echo
            echo "  -t <txid>      the root-of-trust TXID (mandatory)"
            echo
            echo "  -a <address>   the Bitcoin address of the chain (mandatory, for now)"
            echo
            echo "  -n <btcnet>    the Bitcoin network you are running on: mainnet, testnet or regtest"
            echo
            echo "  -b             starts a bitcoind regtest daemon in the specified directory"
            echo "                 and generates funds for the Catena server in the '$conffile'"
            echo "                 config file"
            exit 0
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

conffile=$datadir/config.ini

## Example config file
#pubkey=...
#txid=...
#btc_env=mainnet

if [ ! -f "$conffile" ]; then
    echo "ERROR: No '$conffile' config file found, please create one."
    exit 1
fi

# source the config file directly
. $conffile

if [ -z "$txid" ]; then
    echo "ERROR: You must specify the root-of-trust TXID of the Catena chain using -t <txid> or in the config file $conffile using txid=<txid>"
    exit 1
fi

if [ -z "$pubkey" ]; then
    echo "ERROR: You must specify the address of the Catena chain using -a <address> or in the config file $conffile using pubkey=<address>"
    exit 1
fi

# if no *.class files, then compile
if [ ! -d $scriptdir/target/ ]; then
    echo
    echo "Compiling..."
    echo
    mvn compile
fi

echo
echo "Running Catena client with:"
echo " * directory: $datadir"
echo " * chain address: $pubkey"
echo " * root-of-trust TXID: $txid"
echo " * btc net: '$btc_env'"
[ -n "$*" ] && echo " * extra args: $*"
echo
mvn exec:java -Dexec.mainClass=org.catena.client.ClientApp -Dexec.cleanupDaemonThreads=false -Dexec.args="$txid $pubkey $btc_env $datadir $*"
echo
