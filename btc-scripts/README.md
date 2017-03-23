## `bitcoind` and `bitcoin-cli` notes

Can get extra `bitcoind` help messages using `-help-debug` flag:

    bitcoind -help-debug -help

The `-acceptnonstdtxn` flag is used only in regtest/testnet mode and defaults to 1, meaning it accepts non-standard TXs. We want to set this to 0 to ensure Catena transactions are accepted as standard transactions. 

By default debug output goes to the `debug.log` file in the datadir, which defaults to `~/.bitcoin/regtest` on the regtest network. 

Bitcoin CLI help:

    ./cli.sh help

Bitcoin CLI command example:

    bitcoin-cli -regtest -rpcport=8332 -rpcuser=yourrpcuser -rpcpassword=yourrpcpass <command>

...where some useful commands are:

 - `generate <num>`
 - `getinfo`
 - `getbalance`
 - `sendtoaddress <address> <num-btc>`
 - `getrawtransaction`
   + use `<txid> verbose` for full info
 - `getmempoolinfo`
   + see the size for the # of transactions waiting
 - `getrawmempoolinfo`
 - `getblockhash`
 - `getblock`
   + use `getblockhash` first to get the hash of a block
 - `gettxout <txid> <vout>`
 - `gettxoutproof <txid> [<blockhash>]`
 - `verifytxoutproof <proof>`
 - `createrawtransaction`, `signrawntransaction`, `fundrawtransaction`, etc.
 - `estimatefee <nblocks>`
 - `estimatesmartfee <nblocks>`
 - `getnewaddress`
 - `gettransaction <txid>`
 - `getwalletinfo`
 - `listtransactions <account> <count> <from>`
 - `listunspent`

